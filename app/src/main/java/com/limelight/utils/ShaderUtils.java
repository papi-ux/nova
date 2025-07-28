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
    public static final String FRAGMENT_SHADER_FAKE_3D =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES s_Texture;\n" +
                    "uniform float u_xOffset;\n" +
                    "uniform float u_xScale;\n" +
                    "const float EDGE_FADE = 0.05;\n" + // Schmaler Rand für die Überblendung
                    "void main() {\n" +
                    "  vec2 shiftedCoord = vec2((v_TexCoord.x * u_xScale) + u_xOffset, v_TexCoord.y);\n" +
                    "  vec4 originalColor = texture2D(s_Texture, v_TexCoord);\n" +
                    "  vec4 shiftedColor = texture2D(s_Texture, clamp(shiftedCoord, 0.0, 1.0));\n" +
                    "\n" +
                    "  float edgeFactor = 1.0;\n" +
                    "  if (v_TexCoord.x < EDGE_FADE) {\n" +
                    "    edgeFactor = smoothstep(0.0, EDGE_FADE, v_TexCoord.x);\n" +
                    "  } else if (v_TexCoord.x > 1.0 - EDGE_FADE) {\n" +
                    "    edgeFactor = smoothstep(1.0, 1.0 - EDGE_FADE, v_TexCoord.x);\n" +
                    "  }\n" +
                    "\n" +
                    "  gl_FragColor = mix(originalColor, shiftedColor, edgeFactor);\n" +
                    "}";
    public static final String FRAGMENT_SHADER_DIBR_DYN_CONVERGENCE_3D =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES s_ColorTexture;\n" +
                    "uniform sampler2D s_DepthTexture;\n" +
                    "uniform float u_parallax;\n" +
                    "uniform float u_convergence;\n" +
                    "uniform vec2 u_focusPoint;\n" +
                    "void main() {\n" +
                    "  float depth = texture2D(s_DepthTexture, v_TexCoord).r;\n" +
                    "  float ai_shift = u_parallax * (depth - 0.5) * 0.5;\n" +
                    "\n" +
                    "  // Erzeuge einen Faktor, der am Fokuspunkt 1.0 ist und zu den Rändern hin sanft auf 0.0 abfällt.\n" +
                    "  float dist = distance(v_TexCoord, u_focusPoint);\n" +
                    "  float centerFactor = 1.0 - smoothstep(0.1, 0.7, dist);\n" +
                    "\n" +
                    "  // Wende den Fokus-Faktor auf den gesamten 3D-Effekt an.\n" +
                    "  float total_shift = (u_convergence + ai_shift) * centerFactor;\n" +
                    "  vec2 shiftedCoord = vec2(v_TexCoord.x - total_shift, v_TexCoord.y);\n" +
                    "\n" +
                    "  // Lese die originale und die verschobene Farbe.\n" +
                    "  vec4 originalColor = texture2D(s_ColorTexture, v_TexCoord);\n" +
                    "  vec4 shiftedColor = texture2D(s_ColorTexture, clamp(shiftedCoord, 0.0, 1.0));\n" +
                    "\n" +
                    "  // Berechne einen Mischfaktor basierend auf der Stärke der Verschiebung.\n" +
                    "  // Bei starken Verschiebungen (harten Kanten) wird mehr vom Originalbild beigemischt,\n" +
                    "  // um das Flimmern zu reduzieren.\n" +
                    "  float shiftMagnitude = abs(ai_shift) / u_parallax;\n" +
                    "  float artifactBlendFactor = 1.0 - smoothstep(0.25, 0.4, shiftMagnitude);\n" +
                    "\n" +
                    "  // Mische das 3D-Bild mit dem 2D-Originalbild an den problematischen Stellen.\n" +
                    "  gl_FragColor = mix(originalColor, shiftedColor, artifactBlendFactor);\n" +
                    "}";
    public static final String FRAGMENT_SHADER_BILATERAL_BLUR =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D s_InputTexture;\n" +
                    "uniform vec2 u_texelSize;\n" +
                    "const int KERNEL_RADIUS = 4;\n" +
                    "const float DEPTH_THRESHOLD = 0.02;\n" + // Wie groß der Tiefenunterschied sein darf
                    "void main() {\n" +
                    "  float centerDepth = texture2D(s_InputTexture, v_TexCoord).r;\n" +
                    "  float totalWeight = 0.0;\n" +
                    "  float smoothedDepth = 0.0;\n" +
                    "  for (int x = -KERNEL_RADIUS; x <= KERNEL_RADIUS; x++) {\n" +
                    "    for (int y = -KERNEL_RADIUS; y <= KERNEL_RADIUS; y++) {\n" +
                    "      vec2 offset = vec2(x, y) * u_texelSize;\n" +
                    "      float sampleDepth = texture2D(s_InputTexture, v_TexCoord + offset).r;\n" +
                    "      // Berechne die Gewichtung basierend auf dem Tiefenunterschied\n" +
                    "      float depthDiff = abs(centerDepth - sampleDepth);\n" +
                    "      float weight = 1.0 - smoothstep(0.0, DEPTH_THRESHOLD, depthDiff);\n" +
                    "      smoothedDepth += sampleDepth * weight;\n" +
                    "      totalWeight += weight;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  // Berechne den gewichteten Durchschnitt\n" +
                    "  gl_FragColor = vec4(vec3(smoothedDepth / totalWeight), 1.0);\n" +
                    "}";
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