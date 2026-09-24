#version 450

// Three planes in, one colour out. The decoder writes Y, Cb and Cr as separate single channel
// images, which is the only thing it can write, and a screen wants RGB.
layout(set = 0, binding = 0) uniform sampler2D luma;
layout(set = 0, binding = 1) uniform sampler2D chroma_b;
layout(set = 0, binding = 2) uniform sampler2D chroma_r;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;

void main() {
    // Full range Rec. 709, which is what the host encodes and says so in its profile token. Not a
    // guess and not a default: the bitstream has fields for primaries and range that nothing writes
    // yet, so both ends agree out of band or one of them is wrong. Reading a full range frame with
    // the limited range offset and gain crushes black and clips white while still looking like a
    // picture, which is the kind of wrong nobody reports as a bug.
    float y = texture(luma, uv).r;
    float cb = texture(chroma_b, uv).r - 0.5;
    float cr = texture(chroma_r, uv).r - 0.5;

    colour = vec4(
        y + 1.5748 * cr,
        y - 0.1873 * cb - 0.4681 * cr,
        y + 1.8556 * cb,
        1.0);
}
