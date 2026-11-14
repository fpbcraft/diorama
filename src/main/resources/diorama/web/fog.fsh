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
uniform float CloudSpan;
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
    // Broad banks remain readable at overview zoom. Rotated, unequal periods avoid tiled mottling.
    vec2 uv = world / CloudSpan;
    vec2 rotated = mat2(.8, -.6, .6, .8) * uv;
    float broad = texture(Sampler1, uv + drift * .18).r;
    float detail = texture(Sampler1, rotated * 1.73 - drift * .12).g;
    float cloud = broad * .82 + detail * .18;
    float lit = texture(Sampler1, uv + drift * .18 + vec2(-.025, -.018)).r;
    float edge = smoothstep(.08, .97, soft + (cloud - .5) * .12);
    float alpha = 1.0 - min(known, edge);
    float highlight = clamp(smoothstep(.22, .78, cloud) + (cloud - lit) * 3.0, 0.0, 1.0);
    vec3 color = mix(vec3(.76, .81, .89), vec3(.98, .985, 1.0), highlight);
    color += (cloud - .5) * .045;
    fragColor = vec4(color * alpha, alpha); // Explicit premultiplied output.
}
