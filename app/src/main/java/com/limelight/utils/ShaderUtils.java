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

                    // --- VIGNETTE-EINSTELLUNGEN ZUM ANPASSEN ---\n" +
                    "const float vignette_start = 0.70;\n" + // Ausblenden beginnt bei 85% der Distanz zur Seite
                    "const float vignette_end = 1.0;\n" +   // Ausblenden ist bei 100% abgeschlossen

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
                    "    if (u_parallax > 0.0) {\n" +
                    "        ai_shift = max(ai_shift, 0.0);\n" +
                    "    } else if (u_parallax < 0.0) {\n" +
                    "        ai_shift = min(ai_shift, 0.0);\n" +
                    "    }\n" +
                    "\n" +
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
                    "  }\n" +
                    "\n" +
                    "  float total_shift = ai_shift + combined_ai_shift;\n" +

                    // --- HORIZONTALE VIGNETTE-LOGIK START ---\n" +
                    "  float h_dist = abs(v_TexCoord.x - 0.5) * 2.0;\n" +
                    "  float vignette_factor = 1.0 - smoothstep(vignette_start, vignette_end, h_dist);\n" +
                    "  float final_shift = total_shift * vignette_factor;\n" +
                    // --- HORIZONTALE VIGNETTE-LOGIK ENDE ---\n" +

                    "  vec2 shiftedCoord = vec2(v_TexCoord.x - final_shift, v_TexCoord.y);\n" +
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
                    "    float debugDepth = final_shift;\n" +
                    "    vec3 debugTint = vec3(0.0);\n" +
                    "    if (debugDepth > 0.0) { debugTint.r = debugDepth * 50.0; }\n" +
                    "    else { debugTint.b = -debugDepth * 50.0; }\n" +
                    "    finalColor.rgb += debugTint;\n" +
                    "  }\n" +
                    "  gl_FragColor = finalColor;\n" +
                    "}\n";

    public static final String FRAGMENT_SHADER_GAUSSIAN_BLUR =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D s_InputTexture;\n" +
                    "uniform vec2 u_texelSize;\n" +

                    // NEU: Diese Uniform steuert die Richtung des Weichzeichners
                    "uniform vec2 u_blurDirection;\n" +

                    // Die Stärke des Weichzeichners (Radius)
                    "const float blurRadius = 50.0;\n" +
                    // Die Qualität/Sigma des Weichzeichners
                    "const float sigma = 100.0;\n" +

                    "void main() {\n" +
                    "  vec4 sum = vec4(0.0);\n" +
                    "  float weightSum = 0.0;\n" +

                    "  for (float i = -blurRadius; i <= blurRadius; i++) {\n" +
                         // Berechne die Gewichtung für diesen Pixel basierend auf der Distanz"
    "      float weight = exp(-(i*i) / (2.0 * sigma * sigma));\n" +
                  // Lies den Farbwert an der verschobenen Position"
            "      sum += texture2D(s_InputTexture, v_TexCoord + i * u_texelSize * u_blurDirection) * weight;\n" +
            "      weightSum += weight;\n" +
            "  }\n" +
              // Gib den gewichteten Durchschnitt aus
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