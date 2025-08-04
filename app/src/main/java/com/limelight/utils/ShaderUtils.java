package com.limelight.utils;

/**
 * Enthält die GLSL-Shader-Programme, die für das 3D-Rendering benötigt werden.
 */
public class ShaderUtils {

    public static final String VERTEX_SHADER =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = a_Position;\n" +
                    "  v_TexCoord = a_TexCoord;\n" +
                    "}";
    public static final String FRAGMENT_SHADER_DUAL_BUBBLE_3D =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES s_ColorTexture;\n" +
                    "uniform sampler2D s_DepthTexture;\n" +
                    "uniform float u_parallax;\n" +
                    "uniform float u_convergence;\n" +
                    "uniform vec2 u_focusPoint;\n" +
                    "uniform vec2 u_minFocusPoint;\n" +
                    "uniform bool u_debugMode;\n" +
                    "void main() {\n" +
                    // --- Ihre Logik, aber korrigiert ---
                    "  float depth = texture2D(s_DepthTexture, v_TexCoord).r;\n" +
                    "  float ai_shift = u_parallax * (depth - 0.5) * 0.5;\n" +
                    "\n" +
                    // 1. Berechne die Stärke beider Blasen
                    "  float dist = distance(v_TexCoord, u_focusPoint);\n" +
                    "  float bubbleRadius = 0.9;\n" + // Die Gesamtgröße der Blase
                    "  float t_pos = clamp(dist / bubbleRadius, 0.0, 1.0);\n" +
                    // Normalisiere den Abstand
                    "  float centerFactor = 1.0 - pow(t_pos, 1.0);\n" + // Erzeuge eine parabolische Kurve
                    //   "  float centerFactor = 1.0 - smoothstep(0.1, 0.7, dist);\n" +
                    "  float dist_min = distance(v_TexCoord, u_minFocusPoint);\n" +
                    "  float t_pos_min = clamp(dist_min / bubbleRadius, 0.0, 1.0);\n" +
                    "  float centerFactorMin = 1.0 - pow(t_pos_min, 2.0);\n" +
                    "  float combinedFactor = 0.0;\n" +
                    "  if (centerFactor > centerFactorMin) {\n" +
                    "    combinedFactor = u_convergence * centerFactor;\n" +
                    "  } else {\n" +
                    "    combinedFactor = -u_convergence * centerFactorMin;\n" +
                    "  }\n" +
                    "\n" +
                    // 2. Erzeuge einen positiven Shift für die nahe Blase und einen negativen für die ferne
                    "  float positive_shift = u_convergence * centerFactor;\n" +
                    "  float negative_shift = -u_convergence * centerFactorMin;\n" + // Invertiere den Shift für die ferne Blase
                    "\n" +
                    // 3. Kombiniere die Shifts und wende die globale Konvergenz an
                    "  float combined_ai_shift = positive_shift + negative_shift;\n" +
                    "  float total_shift = ai_shift + combined_ai_shift;\n" +
                    "  vec2 shiftedCoord = vec2(v_TexCoord.x - total_shift, v_TexCoord.y);\n" +
                    "\n" +
                    // --- Der Rest Ihrer bewährten Logik ---
                    "  vec4 originalColor = texture2D(s_ColorTexture, v_TexCoord);\n" +
                    "  vec4 shiftedColor = texture2D(s_ColorTexture, clamp(shiftedCoord, 0.0, 1.0));\n" +
                    //"  float shiftMagnitude = abs(ai_shift) / max(u_parallax, 0.001);\n" +
                    //  "  float artifactBlendFactor = 1.0 - smoothstep(0.25, 0.4, shiftMagnitude);\n" +
                    "  vec4 finalColor = shiftedColor;\n" +
                    "\n" +
                    // --- Debug-Farben ---
                    "  if (u_debugMode) {\n" +
                    "    float debugDepth = total_shift;\n" + // Visualisiere den kombinierten Shift
                    "    vec3 debugTint = vec3(0.0);\n" +
                    "    if (debugDepth > 0.0) { debugTint.r = debugDepth * 50.0; }\n" +
                    "    else { debugTint.b = -debugDepth * 50.0; }\n" +
                    "    finalColor.rgb += debugTint;\n" +
                    "  }\n" +
                    "  gl_FragColor = finalColor;\n" +
                    "}\n";

    public static final String FRAGMENT_SHADER_BILATERAL_BLUR =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D s_InputTexture;\n" +
                    "uniform vec2 u_texelSize;\n" +
                    "void main() {\n" +
                    "  vec4 sum = vec4(0.0);\n" +
                    // Simple 9-tap Gaussian blur. This makes the depth map much softer.
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 4.0 * u_texelSize) * 0.05;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 3.0 * u_texelSize) * 0.09;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 2.0 * u_texelSize) * 0.12;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 1.0 * u_texelSize) * 0.15;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord) * 0.18;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 1.0 * u_texelSize) * 0.15;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 2.0 * u_texelSize) * 0.12;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 3.0 * u_texelSize) * 0.09;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 4.0 * u_texelSize) * 0.05;\n" +
                    "  gl_FragColor = sum;\n" +
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