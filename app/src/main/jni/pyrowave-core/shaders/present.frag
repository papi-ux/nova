#version 450

// Three planes in, one colour out. The decoder writes Y, Cb and Cr as separate single channel
// images, which is the only thing it can write, and a screen wants RGB.
layout(set = 0, binding = 0) uniform sampler2D luma;
layout(set = 0, binding = 1) uniform sampler2D chroma_b;
layout(set = 0, binding = 2) uniform sampler2D chroma_r;

// The inverse colour matrix, handed in rather than compiled in, because this shader serves two
// colourimetries now and they differ only by these four numbers. Full range either way, so there is
// no offset or gain to undo, and the transfer function is left exactly as it arrived: an SDR frame
// carries sRGB values into an sRGB swapchain, and an HDR one carries PQ values into an ST.2084
// swapchain. Converting either would be converting twice.
//
// Which matrix it is comes from the profile token the two ends agreed on before a frame was sent. The
// bitstream has fields for primaries and range that nothing writes yet, so both ends agree out of band
// or one of them is wrong, and reading a full range frame with the limited range offset and gain
// crushes black and clips white while still looking like a picture, which is the kind of wrong nobody
// reports as a bug.
layout(push_constant) uniform Matrix {
    float cr_to_r;
    float cb_to_g;
    float cr_to_g;
    float cb_to_b;
} matrix;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;

void main() {
    float y = texture(luma, uv).r;
    float cb = texture(chroma_b, uv).r - 0.5;
    float cr = texture(chroma_r, uv).r - 0.5;

    colour = vec4(
        y + matrix.cr_to_r * cr,
        y + matrix.cb_to_g * cb + matrix.cr_to_g * cr,
        y + matrix.cb_to_b * cb,
        1.0);
}
