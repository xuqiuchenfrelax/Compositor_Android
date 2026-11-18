#!/usr/bin/env python3
"""Verify Android locale catalogs, format arguments and UI resource references without a build."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "apps/android/app/src/main/res"
SOURCE = ROOT / "apps/android/app/src/main/java/com/pojia/compositor/mobile"
NEUTRAL_TEXT = {"px", "sRGB", "Compositor", "72 DPI", "300 DPI", "1080p", "4K", "100%"}
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")


def catalog(folder: str) -> tuple[dict[str, str], set[str], list[str]]:
    strings: dict[str, str] = {}
    not_translated: set[str] = set()
    errors: list[str] = []
    for path in sorted((RES / folder).glob("*.xml")):
        for element in ET.parse(path).getroot().findall("string"):
            name = element.attrib["name"]
            if name in strings:
                errors.append(f"Duplicate string {folder}/{name}")
            strings[name] = "".join(element.itertext())
            if element.get("translatable") == "false":
                not_translated.add(name)
    return strings, not_translated, errors


def signature(value: str) -> list[tuple[str, str]]:
    # A literal percent consumes both characters before placeholder scanning.
    return sorted((index or "implicit", kind) for index, kind in PLACEHOLDER.findall(value.replace("%%", "")))


def plural_catalog(folder: str) -> tuple[dict[str, dict[str, str]], list[str]]:
    plurals: dict[str, dict[str, str]] = {}
    errors: list[str] = []
    valid_quantities = {"zero", "one", "two", "few", "many", "other"}
    for path in sorted((RES / folder).glob("*.xml")):
        for element in ET.parse(path).getroot().findall("plurals"):
            name = element.attrib["name"]
            if name in plurals:
                errors.append(f"Duplicate plurals {folder}/{name}")
            items: dict[str, str] = {}
            for item in element.findall("item"):
                quantity = item.get("quantity", "")
                if quantity not in valid_quantities:
                    errors.append(f"Invalid plural quantity: {folder}/{name}/{quantity}")
                if quantity in items:
                    errors.append(f"Duplicate plural quantity: {folder}/{name}/{quantity}")
                items[quantity] = "".join(item.itertext())
            if "other" not in items:
                errors.append(f"Missing plural other: {folder}/{name}")
            # English requires one/other; Chinese CLDR uses only other.
            # Requiring matching quantities across languages would reject valid Chinese.
            if folder == "values" and "one" not in items:
                errors.append(f"Missing English plural one: {name}")
            reference = signature(items.get("other", ""))
            for quantity, value in items.items():
                if signature(value) != reference:
                    errors.append(f"Plural format argument mismatch: {folder}/{name}/{quantity}")
            plurals[name] = items
    return plurals, errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    english, neutral, errors = catalog("values")
    chinese, _, zh_errors = catalog("values-zh-rCN")
    errors += zh_errors
    required = set(english) - neutral
    missing = sorted(required - set(chinese))
    unexpected = sorted(set(chinese) - set(english))
    if missing:
        errors.append(f"Missing simplified Chinese resources: {missing}")
    if unexpected:
        errors.append(f"Chinese resources without English defaults: {unexpected}")
    for key in required & set(chinese):
        if signature(english[key]) != signature(chinese[key]):
            errors.append(f"Format argument mismatch: {key}")
    english_plurals, plural_errors = plural_catalog("values")
    chinese_plurals, zh_plural_errors = plural_catalog("values-zh-rCN")
    errors += plural_errors + zh_plural_errors
    missing_plurals = sorted(set(english_plurals) - set(chinese_plurals))
    unexpected_plurals = sorted(set(chinese_plurals) - set(english_plurals))
    if missing_plurals:
        errors.append(f"Missing simplified Chinese plurals: {missing_plurals}")
    if unexpected_plurals:
        errors.append(f"Chinese plurals without English defaults: {unexpected_plurals}")
    for key in set(english_plurals) & set(chinese_plurals):
        if signature(english_plurals[key].get("other", "")) != signature(chinese_plurals[key].get("other", "")):
            errors.append(f"Plural translation format argument mismatch: {key}")
    refs: set[str] = set()
    plural_refs: set[str] = set()
    ui_literals: list[dict[str, object]] = []
    scanned_sources: list[str] = []
    for path in sorted(SOURCE.rglob("*.kt")):
        source = path.read_text()
        refs.update(re.findall(r"R\.string\.(\w+)", source))
        plural_refs.update(re.findall(r"R\.plurals\.(\w+)", source))
        scanned_sources.append(str(path.relative_to(ROOT)))
        for line_number, line in enumerate(source.splitlines(), 1):
            for literal in re.findall(r'(?:\bText\(|(?:contentDescription|stateDescription)\s*=\s*)"([^"\n]*)"', line):
                # Templates can contain source identifiers, not visible English.
                # Still reject literal prose around a dynamic user/content value.
                visible = re.sub(r"\$\{[^}]*\}|\$[A-Za-z_]\w*", "", literal)
                if literal in NEUTRAL_TEXT or not re.search(r"[A-Za-z]", visible):
                    continue
                ui_literals.append({"file": str(path.relative_to(ROOT)), "line": line_number, "literal": literal})
    unknown_refs = sorted(refs - set(english))
    if unknown_refs:
        errors.append(f"Unknown string resource references: {unknown_refs}")
    unknown_plural_refs = sorted(plural_refs - set(english_plurals))
    if unknown_plural_refs:
        errors.append(f"Unknown plurals resource references: {unknown_plural_refs}")
    if ui_literals:
        errors.append("Hardcoded direct UI text remains in production Kotlin sources")
    locales = ET.parse(RES / "xml/locales_config.xml").getroot()
    declared = [item.attrib["{http://schemas.android.com/apk/res/android}name"] for item in locales]
    if declared != ["en", "zh-CN"]:
        errors.append(f"Unexpected supported locales: {declared}")
    manifest = ET.parse(ROOT / "apps/android/app/src/main/AndroidManifest.xml").getroot()
    if manifest.find("application").attrib.get("{http://schemas.android.com/apk/res/android}localeConfig") != "@xml/locales_config":
        errors.append("Manifest does not declare locale config")
    report = {
        "passed": not errors,
        "englishDefaultStrings": len(english),
        "simplifiedChineseStrings": len(chinese),
        "englishDefaultPlurals": len(english_plurals),
        "simplifiedChinesePlurals": len(chinese_plurals),
        "pluralQuantities": {"values": {key: sorted(items) for key, items in english_plurals.items()},
            "values-zh-rCN": {key: sorted(items) for key, items in chinese_plurals.items()}},
        "nonTranslatableDefaults": sorted(neutral),
        "coveredUiFiles": scanned_sources,
        "declaredLocales": declared,
        "hardcodedCoveredUiLiterals": ui_literals,
        "errors": errors,
        "scope": "Static resource integrity and direct Text/semantics literals in every production Kotlin file; complex expressions, runtime rendering, layout and translated upstream exceptions require separate validation.",
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("passed", "englishDefaultStrings", "simplifiedChineseStrings", "errors")}, ensure_ascii=False))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
