#version 330
#extension GL_ARB_separate_shader_objects : require
#include <myriad:effect.glsl>

// Halftone dots in wisps, as smoke curls: layered noise whose places are warped by more noise, drifting slowly. One
// pattern across the whole screen, each background showing its part of it. Options: Color, Amount, Size, Scale, Drift.

// How thick the wisps are at uv (0 to 1 across the screen): about 0.2 to 0.8.
float ditherField(vec2 uv, vec2 size, float t, float scale) {
    float aspect = size.x / max(size.y, 1.0);
    vec2 p = vec2(uv.x * aspect, uv.y) * (2.4 / scale);
    vec2 warp = vec2(effectFbm(p + vec2(t * 0.04, 0.0)), effectFbm(p + vec2(3.7, -t * 0.04)));
    return effectFbm(p + warp * 1.7 + vec2(-t * 0.025, t * 0.012));
}

vec4 effect(EffectInput e) {
    vec4 color = effectOption(0);
    float amount = mix(0.1, 1.0, effectOption(1).x);
    float size = floor(mix(1.0, 4.0, effectOption(2).x) * 10.0 + 0.5) / 10.0;
    float scale = floor(mix(0.5, 3.0, effectOption(3).x) * 10.0 + 0.5) / 10.0;
    float t = effectOption(4).x > 0.5 ? e.time : 0.0;
    // Only the thicker parts of the smoke, more of it the higher Amount is.
    float f = smoothstep(0.68 - amount * 0.28, 0.9, ditherField(e.uv, e.resolution, t, scale));
    // Dots a dot apart, each shown where the field beats its place in an ordered pattern: halftone, not noise.
    vec2 cell = floor(e.pixel / size);
    bool dot = f > 0.0 && mod(cell.x, 2.0) < 0.5 && mod(cell.y, 2.0) < 0.5 && f > effectBayer4(floor(cell * 0.5)) + 0.03;
    return dot ? vec4(color.rgb, color.a * (0.45 + 0.55 * f)) : vec4(0.0);
}
