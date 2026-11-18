package com.pojia.compositor.mobile

/** RGBA8 sRGB arithmetic from the shared core; inputs and output are premultiplied. */
internal object GpuBlendFunctions {
    val source = """
        // The CPU passes a quantized global alpha for RGBA8 source surfaces.
        // Integer division avoids a second independent floating-point rounding.
        vec4 cpOpacityPixel(vec4 source, uint q) {
            uvec4 bytes = uvec4(floor(clamp(source, 0.0, 1.0) * 255.0 + 0.5));
            return vec4((bytes * q + 127u) / 255u) / 255.0;
        }
        vec4 cpRoundPixel(vec4 value) {
            return floor(clamp(value, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
        }
        int cpRoundRatio(int n, int d) {
            return n >= 0 ? (n + d / 2) / d : -((-n + (d - 1) / 2) / d);
        }
        int cpIntegerLum(ivec3 c) { return 77 * c.r + 151 * c.g + 28 * c.b; }
        int cpIntegerSat(ivec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
        ivec3 cpIntegerSetSat(ivec3 c, int target) {
            int lo = min(min(c.r, c.g), c.b), hi = max(max(c.r, c.g), c.b);
            if (hi == lo) return ivec3(0);
            int scale = target * 65536 / (hi - lo);
            for (int i = 0; i < 3; ++i)
                c[i] = c[i] == lo ? 0 : c[i] == hi ? target : ((c[i] - lo) * scale + 32768) / 65536;
            return c;
        }
        vec4 cpIntegerHsl(vec4 source, vec4 destination, uint opacityByte, int mode) {
            ivec4 s = ivec4(floor(cpOpacityPixel(source, opacityByte) * 255.0 + 0.5));
            ivec4 d = ivec4(floor(clamp(destination, 0.0, 1.0) * 255.0 + 0.5));
            if (s.a == 0) return destination;
            if (d.a == 0) return vec4(s) / 255.0;
            int cap = (s.a * d.a + 127) / 255;
            ivec3 sc = (s.rgb * d.a + 127) / 255, dc = (d.rgb * s.a + 127) / 255;
            ivec3 c;
            int target;
            if (mode == 20) { c = cpIntegerSetSat(sc, cpIntegerSat(dc)); target = cpIntegerLum(dc); }
            else if (mode == 21) { c = cpIntegerSetSat(dc, cpIntegerSat(sc)); target = cpIntegerLum(dc); }
            else if (mode == 22) { c = sc; target = cpIntegerLum(dc); }
            else { c = dc; target = cpIntegerLum(sc); }
            int current = cpIntegerLum(c), delta = cpRoundRatio(target - current, 256);
            c += delta;
            int lo = min(min(c.r, c.g), c.b), hi = max(max(c.r, c.g), c.b);
            int light = cpRoundRatio(current, 256) + delta;
            if (lo < 0 && light > lo)
                for (int i = 0; i < 3; ++i) c[i] = cpRoundRatio(light * (c[i] - lo), light - lo);
            if (hi > cap && hi > light)
                for (int i = 0; i < 3; ++i) c[i] = cpRoundRatio(light * (hi - light) + (c[i] - light) * (cap - light), hi - light);
            ivec3 rgb = clamp((d.rgb * (255 - s.a) + s.rgb * (255 - d.a) + 127) / 255 + c, 0, 255);
            int alpha = s.a + (d.a * (255 - s.a) + 127) / 255;
            return vec4(ivec4(rgb, alpha)) / 255.0;
        }
        float cpBurn(float source, float destination) {
            if (destination >= 1.0) return 1.0;
            if (source <= 0.0) return 0.0;
            return 1.0 - min(1.0, (1.0 - destination) / source);
        }
        float cpDodge(float source, float destination) {
            if (destination <= 0.0) return 0.0;
            if (source >= 1.0) return 1.0;
            return min(1.0, destination / (1.0 - source));
        }
        float cpBlendComponent(float source, float destination, int mode) {
            if (mode == 1) return min(source, destination);
            if (mode == 2) return source * destination;
            if (mode == 3) return cpBurn(source, destination);
            if (mode == 4) return max(0.0, source + destination - 1.0);
            if (mode == 5) return max(source, destination);
            if (mode == 6) return source + destination - source * destination;
            if (mode == 7) return cpDodge(source, destination);
            if (mode == 8) return min(1.0, source + destination);
            if (mode == 9) return destination <= 0.5 ? 2.0 * source * destination
                : 1.0 - 2.0 * (1.0 - source) * (1.0 - destination);
            if (mode == 10) {
                if (source <= 0.5)
                    return destination - (1.0 - 2.0 * source) * destination * (1.0 - destination);
                float curve = destination <= 0.25
                    ? ((16.0 * destination - 12.0) * destination + 4.0) * destination : sqrt(destination);
                return destination + (2.0 * source - 1.0) * (curve - destination);
            }
            if (mode == 11) return source <= 0.5 ? 2.0 * source * destination
                : 1.0 - 2.0 * (1.0 - source) * (1.0 - destination);
            if (mode == 12) return source < 0.5 ? cpBurn(2.0 * source, destination)
                : cpDodge(2.0 * source - 1.0, destination);
            if (mode == 13) return clamp(destination + 2.0 * source - 1.0, 0.0, 1.0);
            if (mode == 14) return source < 0.5 ? min(destination, 2.0 * source)
                : max(destination, 2.0 * source - 1.0);
            if (mode == 16) return abs(destination - source);
            if (mode == 17) return source + destination - 2.0 * source * destination;
            if (mode == 18) return max(0.0, destination - source);
            if (mode == 19) return destination <= 0.0 ? 0.0
                : (source <= 0.0 ? 1.0 : min(1.0, destination / source));
            return source;
        }
        bool cpOpacitySurface(int mode) {
            return mode == 3 || mode == 4 || mode == 7 || mode == 8 || mode == 10
                || mode == 12 || mode == 13 || mode == 14 || mode == 15 || mode == 18 || mode == 19;
        }
        vec4 cpBlendPixel(vec4 source, vec4 destination, float opacity, int mode, uint opacityByte) {
            if (mode >= 20 && mode <= 23) return cpIntegerHsl(source, destination, opacityByte, mode);
            opacity = clamp(opacity, 0.0, 1.0);
            if (mode == 0) {
                uvec4 s = uvec4(floor(cpOpacityPixel(source, opacityByte) * 255.0 + 0.5));
                uvec4 d = uvec4(floor(clamp(destination, 0.0, 1.0) * 255.0 + 0.5));
                return vec4(min(uvec4(255u), s + (d * (255u - s.a) + 127u) / 255u)) / 255.0;
            }
            // These desktop modes quantize opacity before unpremultiplication.
            if (cpOpacitySurface(mode) && opacity < 1.0) {
                source = cpOpacityPixel(source, opacityByte);
                opacity = 1.0;
            }
            float sourceAlpha = source.a * opacity;
            if (sourceAlpha <= 0.0) return destination;
            if (mode == 0 && sourceAlpha >= 1.0) return source;
            float destinationAlpha = destination.a;
            vec3 sourcePremultiplied = source.rgb * opacity;
            float alpha = sourceAlpha + destinationAlpha * (1.0 - sourceAlpha);
            if (mode == 0 || destinationAlpha <= 0.0)
                return cpRoundPixel(vec4(sourcePremultiplied + destination.rgb * (1.0 - sourceAlpha), alpha));
            vec3 sourceColor = clamp(sourcePremultiplied / sourceAlpha, 0.0, 1.0);
            vec3 destinationColor = clamp(destination.rgb / destinationAlpha, 0.0, 1.0);
            vec3 blended;
            if (mode == 15) {
                // Cross-products preserve the exact black source + backdrop == 1 boundary.
                uvec4 s = uvec4(floor(source * 255.0 + 0.5));
                uvec4 d = uvec4(floor(destination * 255.0 + 0.5));
                uvec3 sum = s.rgb * d.a + d.rgb * s.a;
                uint threshold = s.a * d.a;
                blended = vec3(sum.r > threshold ? 1.0 : 0.0,
                    sum.g > threshold ? 1.0 : 0.0, sum.b > threshold ? 1.0 : 0.0);
            } else {
                blended = vec3(cpBlendComponent(sourceColor.r, destinationColor.r, mode),
                    cpBlendComponent(sourceColor.g, destinationColor.g, mode),
                    cpBlendComponent(sourceColor.b, destinationColor.b, mode));
            }
            return cpRoundPixel(vec4((1.0 - sourceAlpha) * destination.rgb
                + (1.0 - destinationAlpha) * sourcePremultiplied
                + sourceAlpha * destinationAlpha * blended, alpha));
        }
    """.trimIndent()
}
