#version 330
#extension GL_ARB_separate_shader_objects : require
#include <myriad:effect.glsl>

// Film grain: each speck lightens or darkens the background a little. Options: Amount, Size, Moving.
vec4 effect(EffectInput e) {
    float amount = mix(0.01, 0.3, effectOption(0).x);
    float size = floor(mix(1.0, 6.0, effectOption(1).x) + 0.5);
    float seed = effectOption(2).x > 0.5 ? floor(e.time * 24.0) : 0.0;
    float n = effectHash(floor(e.pixel / size) + seed * 13.37) - 0.5;
    return vec4(vec3(step(0.0, n)), abs(n) * amount * 2.0);
}
