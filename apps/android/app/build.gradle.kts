import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Package only runtime fixture data; source provenance stays in the repository.
val prepareLineFixtures by tasks.registering(Sync::class) {
    from("../../../compatibility/project_v15") {
        include("*.comp/**", "pixels/**", "expected-v15.json")
    }
    into(layout.buildDirectory.dir("generated/lineFixtures"))
}

val prepareSourceOverFixtures by tasks.registering(Sync::class) {
    from("../../../compatibility/blend/research/p18-cg-sourceover") { include("probe-observations.json") }
    into(layout.buildDirectory.dir("generated/sourceOverFixtures"))
}

/*
 * Keep release identity in one small, reviewable file.  CI or a release
 * wrapper can override these values with -PcompositorVersionCode,
 * -PcompositorVersionName, -PcompositorChannel and
 * -PcompositorSourceRevision without editing the project.
 */
val releaseProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    require(file.isFile) { "Missing Android release identity: ${file.path}" }
    FileInputStream(file).use(::load)
}

fun releaseValue(gradleName: String, fileName: String, fallback: String): String =
    providers.gradleProperty(gradleName).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: releaseProperties.getProperty(fileName, fallback).trim()

fun javaStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val compositorVersionCode = releaseValue("compositorVersionCode", "versionCode", "10000").toIntOrNull()
    ?.also { require(it in 1..2_100_000_000) { "compositorVersionCode must be in Android's positive range" } }
    ?: error("compositorVersionCode must be a positive integer")
val compositorVersionName = releaseValue("compositorVersionName", "versionName", "0.1.0")
    .also { require(Regex("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?").matches(it)) {
        "compositorVersionName must use semantic version syntax: $it"
    } }
val compositorChannel = releaseValue("compositorChannel", "channel", "dev").lowercase()
    .also { require(it in setOf("dev", "staging", "production")) {
        "compositorChannel must be dev, staging or production"
    } }
val compositorSourceRevision = releaseValue("compositorSourceRevision", "sourceRevision", "unknown")
    .also { require(Regex("[0-9A-Za-z._:/+-]+").matches(it)) {
        "compositorSourceRevision contains unsupported characters: $it"
    } }
val compositorNativeAbiVersion = 21
val compositorLabel = when (compositorChannel) {
    "production" -> "Compositor"
    "staging" -> "Compositor Beta"
    else -> "Compositor Dev"
}

// Distribution credentials stay outside source and Gradle command-line arguments.
// Partial configuration fails closed; ordinary local Release builds remain unsigned.
val signingEnvironment = listOf("COMPOSITOR_KEYSTORE_PATH", "COMPOSITOR_STORE_PASSWORD",
    "COMPOSITOR_KEY_ALIAS", "COMPOSITOR_KEY_PASSWORD")
val signingValues = signingEnvironment.map { providers.environmentVariable(it).orNull }
val releaseSigningConfigured = signingValues.all { !it.isNullOrEmpty() }
require(signingValues.all { it.isNullOrEmpty() } || releaseSigningConfigured) {
    "Release signing requires all four COMPOSITOR signing environment variables"
}
val requireReleaseSigning = providers.gradleProperty("compositorRequireReleaseSigning")
    .map(String::toBooleanStrict).getOrElse(false)
require(!requireReleaseSigning || releaseSigningConfigured) { "Release signing credentials are required" }
require(compositorChannel != "production" || releaseSigningConfigured) {
    "Production releases require the configured distribution signing identity"
}
if (releaseSigningConfigured) require(rootProject.file(signingValues[0]!!).isFile) {
    "Release keystore does not exist"
}
val releaseSigningState = if (releaseSigningConfigured) "release" else "unsigned"

android {
    namespace = "com.pojia.compositor.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pojia.compositor.mobile"
        minSdk = 31
        targetSdk = 35
        versionCode = compositorVersionCode
        versionName = compositorVersionName
        resValue("string", "app_name", compositorLabel)
        buildConfigField("String", "COMPOSITOR_VERSION_NAME", javaStringLiteral(compositorVersionName))
        buildConfigField("int", "COMPOSITOR_VERSION_CODE", compositorVersionCode.toString())
        buildConfigField("String", "COMPOSITOR_RELEASE_CHANNEL", javaStringLiteral(compositorChannel))
        buildConfigField("String", "COMPOSITOR_SOURCE_REVISION", javaStringLiteral(compositorSourceRevision))
        buildConfigField("int", "COMPOSITOR_NATIVE_ABI_VERSION", compositorNativeAbiVersion.toString())
        manifestPlaceholders["compositorReleaseChannel"] = compositorChannel
        manifestPlaceholders["compositorVersionName"] = compositorVersionName
        manifestPlaceholders["compositorVersionCode"] = compositorVersionCode.toString()
        manifestPlaceholders["compositorSourceRevision"] = compositorSourceRevision
        manifestPlaceholders["compositorBuildType"] = "base"
        manifestPlaceholders["compositorSigningState"] = "unsigned"
        testInstrumentationRunner = "com.pojia.compositor.mobile.EngineSmokeInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle {
        // In-app language changes must work without downloading another language split.
        language { enableSplit = false }
    }
    packaging {
        // Keep native libraries uncompressed and page-aligned for Android 15+
        // devices with 16 KiB pages.  This is part of the release contract.
        jniLibs.useLegacyPackaging = false
        jniLibs.keepDebugSymbols += "**/libcompositor_android.so"
    }
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/sourceOverFixtures"))
    sourceSets.getByName("androidTest").assets.srcDir("../../../compatibility/project_codec")
    sourceSets.getByName("androidTest").assets.srcDir("../../../compatibility/project_v14")
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/lineFixtures"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (releaseSigningConfigured) create("distribution") {
            storeFile = rootProject.file(signingValues[0]!!)
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
    }
    buildTypes {
        debug {
            manifestPlaceholders["compositorBuildType"] = "debug"
            manifestPlaceholders["compositorSigningState"] = "debug"
        }
        release {
            manifestPlaceholders["compositorBuildType"] = "release"
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("distribution")
            manifestPlaceholders["compositorSigningState"] = releaseSigningState
        }
    }
}
dependencies {
    // Keep versions explicit so offline/CI builds do not depend on a BOM metadata
    // artifact that may be absent from the local mirror. These versions are the
    // Compose line used by the current mobile UI and are kept in lockstep.
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.compose.runtime:runtime-saveable:1.6.8")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.0")
}

val releaseArtifactName = if (releaseSigningConfigured) "app-release.apk" else "app-release-unsigned.apk"
val releaseApk = layout.buildDirectory.file("outputs/apk/release/$releaseArtifactName")
val releaseMetadata = layout.buildDirectory.file("outputs/apk/release/release-metadata.json")

fun sha256(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val nativeChecksums = mapOf(
    "arm64-v8a" to "114f484b32d850752305f3823c80d66af0a9bf2f562992ce8d100edf5c22a757",
    "x86_64" to "4fcb68fda99eef8cc55f4e29129b1c212f7235367298c0e631176621d425fa15",
)
val verifyNativeEngine by tasks.registering {
    inputs.files(nativeChecksums.keys.map { file("src/main/jniLibs/$it/libcompositor_android.so") })
    doLast {
        for ((abi, expected) in nativeChecksums) {
            val library = file("src/main/jniLibs/$abi/libcompositor_android.so")
            check(library.isFile && sha256(library) == expected) {
                "Prebuilt compositor engine for $abi is missing or does not match the pinned SHA-256."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyNativeEngine) }

tasks.register("writeReleaseMetadata") {
    dependsOn("packageRelease")
    outputs.file(releaseMetadata)
    inputs.property("releaseSigningState", releaseSigningState)
    inputs.file(releaseApk)
    doLast {
        val apk = releaseApk.get().asFile
        check(apk.isFile) { "Expected Release APK at ${apk.path}" }
        val output = releaseMetadata.get().asFile
        output.parentFile.mkdirs()
        // No wall-clock field is emitted: the receipt remains reproducible for
        // the same source, toolchain, version, channel and APK bytes.
        output.writeText(
            """{
  "applicationId": "com.pojia.compositor.mobile",
  "versionCode": $compositorVersionCode,
  "versionName": "${compositorVersionName.replace("\\", "\\\\").replace("\"", "\\\"")}",
  "channel": "$compositorChannel",
  "label": "$compositorLabel",
  "buildType": "release",
  "signing": "$releaseSigningState",
  "sourceRevision": "${compositorSourceRevision.replace("\\", "\\\\").replace("\"", "\\\"")}",
  "minSdk": 31,
  "targetSdk": 35,
  "nativeAbis": ["arm64-v8a", "x86_64"],
  "nativeAbiVersion": $compositorNativeAbiVersion,
  "artifact": "$releaseArtifactName",
  "artifactSha256": "${sha256(apk)}"
}
""".trimIndent() + "\n"
        )
    }
}

// AGP registers variant assemble tasks after this script is evaluated, so use
// configureEach instead of looking up assembleRelease eagerly.
tasks.configureEach {
    if (name == "assembleRelease") finalizedBy("writeReleaseMetadata")
}

// Android asset source directories do not carry task dependencies on every AGP version.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("AndroidTestAssets")) dependsOn(prepareLineFixtures)
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn(prepareSourceOverFixtures) }

// AGP's Android-test lint model also reads the generated fixture directories.
// Declare the same producers explicitly so Gradle's task validation cannot run
// the model writer before the fixtures have been synchronized.
tasks.configureEach {
    if (name.contains("AndroidTest") && name.contains("lint", ignoreCase = true)) {
        dependsOn(prepareLineFixtures, prepareSourceOverFixtures)
    }
}
