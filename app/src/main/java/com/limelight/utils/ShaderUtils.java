package com.limelight.utils;

public class ShaderUtils {
    public static final String VERTEX_SHADER =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = a_Position;\n" +
                    "  v_TexCoord = a_TexCoord;\n" +
                    "}";

    public static final String FRAGMENT_SHADER_3D =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES s_ColorTexture;\n" +
                    "uniform sampler2D s_DepthTexture;\n" +
                    "uniform float u_parallax;\n" +
                    "uniform bool u_debugMode;\n" +

                    "void main() {\n" +
                    "  float depth = texture2D(s_DepthTexture, v_TexCoord).r;\n" +
                    "  float parallax_magnitude = abs(u_parallax);\n" +
                    "  float ai_shift = parallax_magnitude * (depth - 0.5) * 1.0 * pow(abs(depth - 0.5)*2.0, 0.7);\n" +


                    "  // --- Dynamische Vignette nur anhand der Randbereiche ---\n" +
                    "  float edgeWidth = 0.01;\n" +
                    "  float depthLeft  = texture2D(s_DepthTexture, vec2(edgeWidth, 0.5)).r;\n" +
                    "  float depthRight = texture2D(s_DepthTexture, vec2(1.0 - edgeWidth, 0.5)).r;\n" +
                    "  float ai_shift_left  = u_parallax * (depthLeft  - 0.5);\n" +
                    "  float ai_shift_right = u_parallax * (depthRight - 0.5);\n" +
                    "  float maxEdgeShift = max(abs(ai_shift_left), abs(ai_shift_right));\n" +

                    "  float vignette_start = mix(0.7, 1.0, clamp(maxEdgeShift / 0.5, 0.0, 1.0));\n" +
                    "  const float vignette_end = 1.0;\n" +

                    "\n" +
                    "\n" +
                    "    if (u_parallax > 0.0) {\n" +
                    "        ai_shift = max(ai_shift, 0.0);\n" +
                    "    } else if (u_parallax < 0.0) {\n" +
                    "        ai_shift = min(ai_shift, 0.0);\n" +
                    "    }\n" +
                    "\n" +
                     "  float h_dist = pow(abs(v_TexCoord.x - 0.5) * 2.0, 1.5);\n" +
                     "  float vignette_factor = 1.0 - smoothstep(vignette_start, vignette_end, h_dist);\n" +
                    "   float final_shift = ai_shift * vignette_factor;\n" +

                    "  vec2 shiftedCoord = vec2(v_TexCoord.x - final_shift, v_TexCoord.y);\n" +
                    "\n" +
                    "  vec4 originalColor = texture2D(s_ColorTexture, v_TexCoord);\n" +
                    "  vec4 shiftedColor = texture2D(s_ColorTexture, clamp(shiftedCoord, 0.0, 1.0));\n" +
                    "  float shiftMagnitude = abs(final_shift) / max(abs(parallax_magnitude), 0.001);\n" +
                    "  float artifactBlendFactor = (1.0 - smoothstep(0.1, 1.0, shiftMagnitude)) * 0.005;\n" +
                    "  vec4 finalColor = mix(shiftedColor, originalColor, artifactBlendFactor);\n" +
                    "\n" +
                    "  if (u_debugMode) {\n" +
                    "    float debugDepth = final_shift;\n" +
                    "    vec3 debugTint = vec3(0.0);\n" +
                    "    if (debugDepth > 0.0) { debugTint.r = debugDepth * 50.0; }\n" +
                    "    else { debugTint.b = -debugDepth * 50.0; }\n" +
                    "    finalColor.rgb += debugTint;\n" +
                    "  }\n" +
                    "  gl_FragColor = finalColor;\n" +
                    "}\n";

    /**
     * An optimized, single-pass Gaussian blur shader that works as a drop-in replacement.
     * It achieves better performance by taking fewer texture samples over the same blur radius.
     * NOTE: For best performance and quality, a two-pass implementation is still highly recommended.
     */
    public static final String OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D s_InputTexture;\n" +
                    "uniform vec2 u_texelSize;\n" +
                    "uniform vec2 u_blurDirection;\n" +

                    "const float blurRadius = 15.0;\n" +
                    "const float blurStep = 5.0;\n" +
                    "const float sigma = 22.0;\n" +

                    "void main() {\n" +
                    "  vec4 sum = vec4(0.0);\n" +
                    "  float weightSum = 0.0;\n" +

                    "  for (float i = -blurRadius; i <= blurRadius; i++) {\n" +
                    "    float sampleDistance = i * blurStep;\n" +

                    "    float weight = exp(-(sampleDistance * sampleDistance) / (2.0 * sigma * sigma));\n" +

                    "    sum += texture2D(s_InputTexture, v_TexCoord + sampleDistance * u_texelSize * u_blurDirection) * weight;\n" +
                    "    weightSum += weight;\n" +
                    "  }\n" +
                    "  gl_FragColor = sum / weightSum;\n" +
                    "}\n";

    public static final String SIMPLE_VERTEX_SHADER =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = a_Position;\n" +
                    "    v_TexCoord = a_TexCoord;\n" +
                    "}\n";

    public static final String SIMPLE_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES u_Texture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(u_Texture, v_TexCoord);\n" +
                    "}\n";
}