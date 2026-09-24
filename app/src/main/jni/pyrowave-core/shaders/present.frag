#version 450

// Three planes in, one colour out. The decoder writes Y, Cb and Cr as separate single channel
// images, which is the only thing it can write, and a screen wants RGB.
layout(set = 0, binding = 0) uniform sampler2D luma;
layout(set = 0, binding = 1) uniform sampler2D chroma_b;
layout(set = 0, binding = 2) uniform sampler2D chroma_r;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;

void main() {
    // BT.709 limited range, which is what a stream carries unless it says otherwise.
    float y = (texture(luma, uv).r - 0.0625) * 1.164383;
    float cb = texture(chroma_b, uv).r - 0.5;
    float cr = texture(chroma_r, uv).r - 0.5;

    colour = vec4(
        y + 1.792741 * cr,
        y - 0.213249 * cb - 0.532909 * cr,
        y + 2.112402 * cb,
        1.0);
}
