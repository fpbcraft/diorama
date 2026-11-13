#version 300 es
precision highp float;
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Previous0;
uniform sampler2D Previous2;
uniform float Reveal;
uniform float GameTime;
uniform vec3 maskWorld;
in vec2 maskUv;
out vec4 fragColor;

// One compositing pass: no volume marching, derivative noise or mutable shader globals.
void main() {
    bool outside = any(lessThan(maskUv, vec2(0.0))) || any(greaterThan(maskUv, vec2(1.0)));
    float known = outside ? 0.0 : texture(Sampler0, maskUv).r;
    float soft = outside ? 0.0 : texture(Sampler2, maskUv).b;
    if (Reveal < 1.0 && !outside) {
        known = mix(texture(Previous0, maskUv).r, known, Reveal);
        soft = mix(texture(Previous2, maskUv).b, soft, Reveal);
    }
    // Fully explored interiors always write transparent black, including on Safari.
    if (known >= .999 && soft >= .999) { fragColor = vec4(0.0); return; }
    vec2 world = maskWorld.xy + maskUv * maskWorld.z;
    float seconds = GameTime * 1200.0;
    vec2 drift = vec2(seconds * .0015, seconds * -.001);
    float cloud = texture(Sampler1, world / 384.0 + drift).r * .65
        + texture(Sampler1, world / 137.0 - drift * .7).g * .35;
    float edge = smoothstep(.10, .96, soft + (cloud - .5) * .16);
    // Missing ground is opaque regardless of filtered coverage or noise.
    float alpha = 1.0 - min(known, edge);
    vec3 color = mix(vec3(.68, .73, .82), vec3(.97, .98, 1.0), smoothstep(.12, .86, cloud));
    fragColor = vec4(color * alpha, alpha); // Explicit premultiplied output.
}
