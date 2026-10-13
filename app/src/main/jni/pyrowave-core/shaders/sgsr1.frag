#version 450

// Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
//
// SGSR 1 (Snapdragon Game Super Resolution 1), a spatial single pass upscaler, from
// SnapdragonGameStudios/snapdragon-gsr@d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e
// sgsr/v1/include/glsl/sgsr1_shader_mobile.frag, BSD-3-Clause (see LICENSE-SGSR1 beside this file).
// Adapted after RubyLight's fused YCbCr Vulkan port,
// RamazanKara/rubylight-android@2293aa2705b42040780b97e4052739be1822043d
// app/src/main/jni/pyrowave-renderer/shaders/sgsr.frag (GPL-3.0), which reads the decoder's planes
// directly so colour conversion and upscaling are one pass.
//
// What Nova changes, and why:
// - Edges are found on luma instead of RGB green, as RubyLight does. Adding one delta to R, G and B
//   moves Rec. 709 luma by that same delta, so the filter's arithmetic is unchanged.
// - The colour is present.frag's exactly: the pushed matrix, chroma centred on 0.5, full range only,
//   chroma sampled at the same coordinate with the same LINEAR sampler, so 4:2:0 and 4:4:4 site as
//   the default path does. Where no edge is found the output is present.frag's output, unclamped.
// - No coordinate flip. uv is present.vert's: (0, 0) is the top left of the viewport and texture
//   row 0 is the top row. Qualcomm's imgCoord offset of (-0.5, +0.5) and its four gathers are kept
//   as written: they index the bilinear quad and its twelve tap ring in texel space, and that
//   footprint is the same whichever way rows are counted.
// - fp32 throughout, where Qualcomm's file is mediump.
// - Where an edge is filtered the base colour is clamped to [0, 1] before the delta is added and
//   after, as RubyLight does: Qualcomm adds the delta to a texture colour that is already in range.
// - A filter whose weights sum to zero or less leaves the pixel as present.frag draws it. Qualcomm
//   divides by that sum unguarded and RubyLight floors it at 1e-8; both give an arbitrary bound there.
// - Edge sharpness is pushed, and Nova pushes Qualcomm's default of 2.0 and nothing else.

layout(set = 0, binding = 0) uniform sampler2D luma;
layout(set = 0, binding = 1) uniform sampler2D chroma_b;
layout(set = 0, binding = 2) uniform sampler2D chroma_r;

// 36 bytes, kept in step with sgsr_push_t in pyrowave_renderer.h.
layout(push_constant) uniform Sgsr {
    vec4 matrix;         // offset 0: cr_to_r, cb_to_g, cr_to_g, cb_to_b, the numbers present.frag gets
    vec4 viewport_info;  // offset 16: 1 / width, 1 / height, width, height of the luma plane
    float edge_sharpness;  // offset 32
} sgsr;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;

float fast_lanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

vec2 weight_y(float dx, float dy, float c, float std_inv) {
    float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * std_inv, 0.0, 1.0);
    float w = fast_lanczos2(x);
    return vec2(w, w * c);
}

void main() {
    float y = texture(luma, uv).r;
    float cb = texture(chroma_b, uv).r - 0.5;
    float cr = texture(chroma_r, uv).r - 0.5;
    vec3 rgb = vec3(
        y + sgsr.matrix.x * cr,
        y + sgsr.matrix.y * cb + sgsr.matrix.z * cr,
        y + sgsr.matrix.w * cb);

    vec2 img_coord = uv * sgsr.viewport_info.zw + vec2(-0.5, 0.5);
    vec2 img_coord_pixel = floor(img_coord);
    vec2 coord = img_coord_pixel * sgsr.viewport_info.xy;
    vec2 pl = img_coord - img_coord_pixel;
    vec4 left = textureGather(luma, coord, 0);

    float edge_vote = abs(left.z - left.y) + abs(y - left.y) + abs(y - left.z);
    if (edge_vote > 8.0 / 255.0) {
        coord.x += sgsr.viewport_info.x;

        vec4 right = textureGather(luma, coord + vec2(sgsr.viewport_info.x, 0.0), 0);
        vec4 up_down;
        up_down.xy = textureGather(luma, coord + vec2(0.0, -sgsr.viewport_info.y), 0).wz;
        up_down.zw = textureGather(luma, coord + vec2(0.0, sgsr.viewport_info.y), 0).yx;

        float mean = (left.y + left.z + right.x + right.w) * 0.25;
        left = left - vec4(mean);
        right = right - vec4(mean);
        up_down = up_down - vec4(mean);
        float centre = y - mean;

        float sum = (((((abs(left.x) + abs(left.y)) + abs(left.z)) + abs(left.w)) +
                      (((abs(right.x) + abs(right.y)) + abs(right.z)) + abs(right.w))) +
                     (((abs(up_down.x) + abs(up_down.y)) + abs(up_down.z)) + abs(up_down.w)));
        float std_inv = 2.181818 / sum;

        vec2 aWY = weight_y(pl.x, pl.y + 1.0, up_down.x, std_inv);
        aWY += weight_y(pl.x - 1.0, pl.y + 1.0, up_down.y, std_inv);
        aWY += weight_y(pl.x - 1.0, pl.y - 2.0, up_down.z, std_inv);
        aWY += weight_y(pl.x, pl.y - 2.0, up_down.w, std_inv);
        aWY += weight_y(pl.x + 1.0, pl.y - 1.0, left.x, std_inv);
        aWY += weight_y(pl.x, pl.y - 1.0, left.y, std_inv);
        aWY += weight_y(pl.x, pl.y, left.z, std_inv);
        aWY += weight_y(pl.x + 1.0, pl.y, left.w, std_inv);
        aWY += weight_y(pl.x - 1.0, pl.y - 1.0, right.x, std_inv);
        aWY += weight_y(pl.x - 2.0, pl.y - 1.0, right.y, std_inv);
        aWY += weight_y(pl.x - 2.0, pl.y, right.z, std_inv);
        aWY += weight_y(pl.x - 1.0, pl.y, right.w, std_inv);

        if (aWY.x > 0.0) {
            float final_y = aWY.y / aWY.x;

            float max_y = max(max(left.y, left.z), max(right.x, right.w));
            float min_y = min(min(left.y, left.z), min(right.x, right.w));
            final_y = clamp(sgsr.edge_sharpness * final_y, min_y, max_y);

            float delta_y = final_y - centre;

            // Smooth high contrast input.
            delta_y = clamp(delta_y, -23.0 / 255.0, 23.0 / 255.0);

            rgb = clamp(clamp(rgb, 0.0, 1.0) + delta_y, 0.0, 1.0);
        }
    }

    colour = vec4(rgb, 1.0);
}
