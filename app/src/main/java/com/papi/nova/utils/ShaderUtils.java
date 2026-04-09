package com.papi.nova.utils;

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
                    "uniform float u_convergence;\n" +
                    "uniform float u_shift;\n" +
                    "uniform bool u_debugMode;\n" +
                    "\n" +
                    "void main() {\n" +
                    "  float depth = texture2D(s_DepthTexture, v_TexCoord).r;\n" +
                    "\n" +
                    "  // Remap depth into symmetric range around convergence\n" +
                    "  float depthDiff;\n" +
                    "  if (depth < u_convergence) {\n" +
                    "    depthDiff = (depth - u_convergence) / u_convergence; // [-1,0]\n" +
                    "  } else {\n" +
                    "    depthDiff = (depth - u_convergence) / (1.0 - u_convergence); // [0,1]\n" +
                    "  }\n" +
                    "\n" +
                    "  float parallax_magnitude = abs(u_parallax);\n" +
                    "  float ai_shift = parallax_magnitude * depthDiff;\n" +
                    "\n" +
                    "  // --- Dynamische Vignette ---\n" +
                    "  float edgeWidth = 0.01;\n" +
                    "  float depthLeft  = texture2D(s_DepthTexture, vec2(edgeWidth, 0.5)).r;\n" +
                    "  float depthRight = texture2D(s_DepthTexture, vec2(1.0 - edgeWidth, 0.5)).r;\n" +
                    "  float ai_shift_left  = u_parallax * (depthLeft  - 0.5);\n" +
                    "  float ai_shift_right = u_parallax * (depthRight - 0.5);\n" +
                    "  float maxEdgeShift = max(abs(ai_shift_left), abs(ai_shift_right));\n" +
                    "  bool isLeftEye = (u_parallax < 0.0);\n" +
                    "  float isLeftEyeIndicator = isLeftEye ? -1.0 : 1.0;\n" +
                    "  float vignette_start = mix(0.7, 1.0, clamp(maxEdgeShift / 0.5, 0.0, 1.0));\n" +
                    "  const float vignette_end = 1.0;\n" +
                    "\n" +
                    "  if ((depth - u_convergence) < 0.0) {\n" +
                    "    ai_shift *= isLeftEye ? u_shift : (1.0-u_shift);\n" +
                    "  } else {\n" +
                    "    ai_shift *= isLeftEye ? (1.0-u_shift) : u_shift;\n" +
                    "  }\n" +
                    "\n" +
                    "  float h_dist = pow(abs(v_TexCoord.x - 0.5) * 2.0, 1.5);\n" +
                    "  float vignette_factor = 1.0 - smoothstep(vignette_start, vignette_end, h_dist);\n" +
                    "  float final_shift = ai_shift * vignette_factor;\n" +
                    "\n" +
                    "  // ---------------- Backward Warping nur horizontal -----------------\n" +
                    "  vec2 srcUV = vec2(v_TexCoord.x - final_shift * isLeftEyeIndicator, v_TexCoord.y);\n" +
                    "  vec4 shiftedColor = texture2D(s_ColorTexture, clamp(srcUV, 0.0, 1.0));\n" +
                    "\n" +
                    "  vec4 originalColor = texture2D(s_ColorTexture, v_TexCoord);\n" +
                    "  float shiftMagnitude = abs(final_shift) / max(abs(parallax_magnitude), 0.001);\n" +
                    "  float artifactBlendFactor = (1.0 - smoothstep(0.1, 1.0, shiftMagnitude)) * 0.005;\n" +
                    "  vec4 finalColor = mix(shiftedColor, originalColor, artifactBlendFactor);\n" +
                    "\n" +
                    "  // ---------------- Rot/Blau Debug -----------------\n" +
                    "  if (u_debugMode) {\n" +
                    "    float debugDepth = final_shift;\n" +
                    "    vec3 debugTint = vec3(0.0);\n" +
                    "    if (debugDepth > 0.0) { debugTint.r = debugDepth * 50.0; }\n" +
                    "    else { debugTint.b = -debugDepth * 50.0; }\n" +
                    "    finalColor.rgb += debugTint;\n" +
                    "  }\n" +
                    "\n" +
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
                    "uniform float u_parallax;\n" +

                    "void main() {\n" +
                    "float blurRadius = 60.0 * u_parallax;\n" +
                    "float blurStep = 2.0 / u_parallax;\n" +
                    "float sigma = 50.0 * u_parallax;\n" +
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

    public static final String EDGE_AWARE_VERTEX_SHADER =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main(){\n" +
                    "  v_TexCoord = a_TexCoord;\n" +
                    "  gl_Position = a_Position;\n" +
                    "}\n";

    public static final String EDGE_AWARE_DEPTH_BLUR_SHADER =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D uDepthMap;\n" +
                    "uniform vec2 u_texelSize;\n" +
                    "uniform bool u_debugMode;\n" +
                    "uniform float u_parallax;\n" +
                    "\n" +
                    "void main(){\n" +
                    "float parallaxFactor = clamp(u_parallax, 0.0, 1.0);\n" +
                    "int radius = int(30.0 * parallaxFactor);\n"+
                    "float sharpness = 1.0 * (1.1 - parallaxFactor);   \n" +
                    "float holeThreshold = 20.0 * parallaxFactor;   \n" +
                    "  float centerDepth = texture2D(uDepthMap, v_TexCoord).r;\n" +
                    "  vec4 sum = vec4(0.0);\n" +
                    "  float weightSum = 0.0;\n" +
                    "\n" +
                    "  // ---------------- Horizontal Blur ----------------\n" +
                    "  for(int i=-radius;i<=radius;i++){\n" +
                    "    vec2 offsetUV = v_TexCoord + vec2(float(i)*u_texelSize.x,0.0);\n" +
                    "    float sampleDepth = texture2D(uDepthMap, offsetUV).r;\n" +
                    "    float w = exp(-abs(sampleDepth-centerDepth)*sharpness);\n" +
                    "    sum += texture2D(uDepthMap, offsetUV) * w;\n" +
                    "    weightSum += w;\n" +
                    "  }\n" +
                    "  vec4 blurred = sum/weightSum;\n" +
                    "\n" +
                    "  // ---------------- Hole Filling ----------------\n" +
                    "  if(abs(blurred.r - centerDepth) > holeThreshold){\n" +
                    "    vec4 fill = vec4(0.0);\n" +
                    "    float fillWeight = 0.0;\n" +
                    "    vec2 offs[4];\n" +
                    "    offs[0] = vec2(u_texelSize.x,0.0);\n" +
                    "    offs[1] = vec2(-u_texelSize.x,0.0);\n" +
                    "    offs[2] = vec2(0.0,u_texelSize.y);\n" +
                    "    offs[3] = vec2(0.0,-u_texelSize.y);\n" +
                    "    for(int k=0;k<4;k++){\n" +
                    "      float d = texture2D(uDepthMap, v_TexCoord+offs[k]).r;\n" +
                    "      if(abs(d-centerDepth)<holeThreshold){\n" +
                    "        fill += texture2D(uDepthMap, v_TexCoord+offs[k]);\n" +
                    "        fillWeight += 1.0;\n" +
                    "      }\n" +
                    "    }\n" +
                    "    if(fillWeight>0.0){ blurred = mix(blurred, fill/fillWeight, 0.7); }\n" +
                    "  }\n" +
                    "\n" +
                    "  // ---------------- Debug Rot/Blau ----------------\n" +
                    "  if(u_debugMode){\n" +
                    "    vec3 debugTint = vec3(0.0);\n" +
                    "    float diff = blurred.r - centerDepth;\n" +
                    "    if(diff > 0.0) debugTint.r = diff*50.0;\n" +
                    "    else debugTint.b = -diff*50.0;\n" +
                    "    blurred.rgb += debugTint;\n" +
                    "  }\n" +
                    "\n" +
                    "  gl_FragColor = blurred;\n" +
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