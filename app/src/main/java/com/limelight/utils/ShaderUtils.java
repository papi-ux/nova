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
                    "uniform bool u_bothEyes;\n" +
                    "void main() {\n" +
                    // --- Basis-3D-Effekt aus der Tiefenkarte ---\n" +
                    "  float depth = texture2D(s_DepthTexture, v_TexCoord).r;\n" +
                    "  float parallax_magnitude = abs(u_parallax);\n" +
                    "  float ai_shift = parallax_magnitude * (depth - 0.5) * 0.5;\n" +
                    "\n" +
                    "  float combined_ai_shift = 0.0;\n" +
                    "  float bubbleRadius = 0.5;\n" +
                    "\n" +
                    "  if (u_bothEyes) {\n" +
                    "    // Modus 1: Beide Augen, alles ist aktiv, kein Beschneiden.\n" +
                    "    float dist = distance(v_TexCoord, u_focusPoint);\n" +
                    "    float t_pos = clamp(dist / bubbleRadius, 0.0, 1.0);\n" +
                    "    float centerFactor = 1.0 - pow(t_pos, 1.0);\n" +
                    "    float positive_shift = u_convergence * centerFactor;\n" +
                    "\n" +
                    "    float dist_min = distance(v_TexCoord, u_minFocusPoint);\n" +
                    "    float t_pos_min = clamp(dist_min / bubbleRadius, 0.0, 1.0);\n" +
                    "    float centerFactorMin = 1.0 - pow(t_pos_min, 2.0);\n" +
                    "    float negative_shift = -u_convergence * centerFactorMin;\n" +
                    "    combined_ai_shift = positive_shift + negative_shift;\n" +
                    "  } else {\n" +
                    "    // Modus 2: Geteilte Steuerung. Parallax beschneidet, Convergence fügt Bubble hinzu.\n" +
                    "\n" +
                    "    // SCHRITT 1: Globalen Shift IMMER basierend auf u_parallax beschneiden.\n" +
                    "    if (u_parallax > 0.0) {\n" +
                    "        ai_shift = max(ai_shift, 0.0);\n" +
                    "    } else if (u_parallax < 0.0) {\n" +
                    "        ai_shift = min(ai_shift, 0.0);\n" +
                    "    }\n" +
                    "\n" +
                    "    // SCHRITT 2: Bubble-Shift UNABHÄNGIG basierend auf u_convergence hinzufügen.\n" +
                    "    if (u_convergence > 0.0) {\n" +
                    "        float dist = distance(v_TexCoord, u_focusPoint);\n" +
                    "        float t_pos = clamp(dist / bubbleRadius, 0.0, 1.0);\n" +
                    "        float centerFactor = 1.0 - pow(t_pos, 1.0);\n" +
                    "        combined_ai_shift = u_convergence * centerFactor;\n" +
                    "    } else if (u_convergence < 0.0) {\n" +
                    "        float dist_min = distance(v_TexCoord, u_minFocusPoint);\n" +
                    "        float t_pos_min = clamp(dist_min / bubbleRadius, 0.0, 1.0);\n" +
                    "        float centerFactorMin = 1.0 - pow(t_pos_min, 2.0);\n" +
                    "        combined_ai_shift = u_convergence * centerFactorMin;\n" +
                    "    }\n" +
                    "    // Wenn u_convergence = 0, bleibt combined_ai_shift einfach 0. Perfekt.\n" +
                    "  }\n" +
                    "\n" +
                    // Kombiniere den (jetzt korrekt beschnittenen) Basis-Shift mit dem (optionalen) Blasen-Shift\n" +
                    "  float total_shift = ai_shift + combined_ai_shift;\n" +
                    "  vec2 shiftedCoord = vec2(v_TexCoord.x - total_shift, v_TexCoord.y);\n" +
                    "\n" +
                    // --- Der Rest Ihrer bewährten Logik ---\n" +
                    "  vec4 originalColor = texture2D(s_ColorTexture, v_TexCoord);\n" +
                    "  vec4 shiftedColor = texture2D(s_ColorTexture, clamp(shiftedCoord, 0.0, 1.0));\n" +
                    "  float shiftMagnitude = abs(total_shift) / max(abs(parallax_magnitude) + abs(u_convergence), 0.001);\n" +
                    "  float artifactBlendFactor = (1.0 - smoothstep(0.1, 1.0, shiftMagnitude)) * 0.005;\n" +
                    "  vec4 finalColor = mix(shiftedColor, originalColor, artifactBlendFactor);\n" +
                    "\n" +
                    // --- Debug-Farben ---\n" +
                    "  if (u_debugMode) {\n" +
                    "    float debugDepth = total_shift;\n" +
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
                    // Erweiterter 13-Tap Gaussian Blur für einen weicheren Effekt
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 6.0 * u_texelSize) * 0.0093;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 5.0 * u_texelSize) * 0.0280;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 4.0 * u_texelSize) * 0.0656;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 3.0 * u_texelSize) * 0.1210;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 2.0 * u_texelSize) * 0.1747;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord - 1.0 * u_texelSize) * 0.1974;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord) * 0.2080;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 1.0 * u_texelSize) * 0.1974;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 2.0 * u_texelSize) * 0.1747;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 3.0 * u_texelSize) * 0.1210;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 4.0 * u_texelSize) * 0.0656;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 5.0 * u_texelSize) * 0.0280;\n" +
                    "  sum += texture2D(s_InputTexture, v_TexCoord + 6.0 * u_texelSize) * 0.0093;\n" +
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