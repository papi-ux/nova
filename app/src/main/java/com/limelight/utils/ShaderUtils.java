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
                    "void main() {\n" +
                    "  float depth = texture2D(s_DepthTexture, v_TexCoord).r;\n" +
                    "  float ai_shift = u_parallax * (depth - 0.5) * 0.5;\n" +
                    "  float total_shift = u_convergence + ai_shift;\n" +
                    "  vec2 shiftedCoord = vec2(v_TexCoord.x - total_shift, v_TexCoord.y);\n" +
                    "\n" +
                    "  vec4 originalColor = texture2D(s_ColorTexture, v_TexCoord);\n" +
                    "  vec4 shiftedColor = texture2D(s_ColorTexture, clamp(shiftedCoord, 0.0, 1.0));\n" +
                    "\n" +
                    "  float outOfBounds = max(0.0, -shiftedCoord.x) + max(0.0, shiftedCoord.x - 1.0);\n" +
                    "  float edgeBlendFactor = 1.0 - smoothstep(0.0, 0.01, outOfBounds);\n" +
                    "\n" +
                    "  // KORREKTUR: Der Übergang für die Artefakt-Unterdrückung wurde sanfter gemacht.\n" +
                    "  // Er beginnt früher (bei 15%) und verläuft über einen größeren Bereich.\n" +
                    "  float shiftMagnitude = abs(ai_shift) / u_parallax;\n" +
                    "  float artifactBlendFactor = 1.0 - smoothstep(0.15, 0.4, shiftMagnitude);\n" +
                    "\n" +
                    "  float finalBlendFactor = min(edgeBlendFactor, artifactBlendFactor);\n" +
                    "\n" +
                    "  gl_FragColor = mix(originalColor, shiftedColor, finalBlendFactor);\n" +
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

    public static final String FRAGMENT_SHADER_BLUR =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D s_Texture;\n" +
                    "uniform vec2 u_textureSize;\n" + // Die Größe der Textur (z.B. 256x256)
                    "void main() {\n" +
                    "  vec2 texelSize = 1.0 / u_textureSize;\n" +
                    "  vec4 result = vec4(0.0);\n" +
                    // Sample 9 Pixel in einem 3x3-Raster um den aktuellen Pixel
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(-texelSize.x, -texelSize.y));\n" +
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(0.0, -texelSize.y));\n" +
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(texelSize.x, -texelSize.y));\n" +
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(-texelSize.x, 0.0));\n" +
                    "  result += texture2D(s_Texture, v_TexCoord);\n" +
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(texelSize.x, 0.0));\n" +
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(-texelSize.x, texelSize.y));\n" +
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(0.0, texelSize.y));\n" +
                    "  result += texture2D(s_Texture, v_TexCoord + vec2(texelSize.x, texelSize.y));\n" +
                    // Bilde den Durchschnitt der 9 Samples
                    "  gl_FragColor = result / 9.0;\n" +
                    "}";
}