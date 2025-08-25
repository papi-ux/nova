package com.limelight.utils;

import static org.jcodec.common.tools.Debug.println;
import java.util.Arrays;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.InterpreterApi;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.Delegate;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Eine eigenständige Klasse, die das gesamte OpenGL-Rendering für 2D- und 3D-Modi verwaltet.
 * Die KI-Berechnung läuft asynchron und schaltet bei Laufzeitfehlern sicher auf die CPU um.
 */
public class Stereo3DRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    public enum RenderMode {
        MODE_2D,
        MODE_FASTPATH_3D,
        MODE_AI_3D_STRONG,
        MODE_AI_3D
    }

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private static final float[] TEXTURE_VERTICES = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;

    private final GLSurfaceView glSurfaceView;
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    private final Context context;

    private SurfaceTexture videoSurfaceTexture;


    private static class DepthResult {
        final ByteBuffer depthMap;
        final float averageDepth;
        final float focusPointX;
        final float focusPointY;
        final float minFocusPointX; // Furthest point X
        final float minFocusPointY; // Furthest point Y

        DepthResult(ByteBuffer map, float avg, float focusX, float focusY, float minFocusX, float minFocusY) {
            depthMap = map;
            averageDepth = avg;
            focusPointX = focusX;
            focusPointY = focusY;
            minFocusPointX = minFocusX;
            minFocusPointY = minFocusY;
        }
    }

    private final AtomicReference<DepthResult> latestDepthResult = new AtomicReference<>();

    private float smoothedAverageSceneDepth = 0.0f;
    private float targetAverageSceneDepth = 0.0f;
    private Surface videoSurface;

    private float targetFocusPointX = 0.5f;
    private float targetFocusPointY = 0.5f;

    private float targetMinFocusPointX = 0.5f;
    private float targetMinFocusPointY = 0.5f;
    private int videoTextureId;
    private int depthMapTextureId;
    private int fboHandle;
    private int fboTextureId;

    private int simple3dProgram;

    private int bilateralBlurProgram;

    private int dibr3dProgram;

    private Interpreter tflite;
    private GpuDelegate gpuDelegate;
    private int modelInputWidth = 256;
    private int modelInputHeight = 256;

    private ByteBuffer previousDepthBytes = null;
    private ExecutorService executorService;

    // Variables for dynamic convergence
    private volatile float averageSceneDepth = 0.5f;

    private volatile float smoothedFocusPointX = 0.5f;
    private volatile float smoothedFocusPointY = 0.5f;

    private volatile float smoothedMinFocusPointX = 0.5f;
    private volatile float smoothedMinFocusPointY = 0.5f;

    private int filteredDepthMapTextureId; // Smoothed version of the depth map
    private int filterFboHandle; // FBO for the blur pass
    private final Object frameLock = new Object();
    private PreferenceConfiguration prefConf;
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);
    private final AtomicBoolean gpuDelegateFailed = new AtomicBoolean(false);

    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private float stereoOffset = 0.01f;
    private RenderMode currentRenderMode = RenderMode.MODE_2D;

    public Stereo3DRenderer(GLSurfaceView view, OnSurfaceReadyListener listener, Context context) {
        this.glSurfaceView = view;
        this.onSurfaceReadyListener = listener;
        this.context = context;

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).position(0);
        textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureVertexBuffer.put(TEXTURE_VERTICES).position(0);
    }

    public void setPrefConfig(PreferenceConfiguration prefConf) {
        this.prefConf = prefConf;
    }

    public Surface getVideoSurface() {
        return videoSurface;
    }

    public void setRenderMode(RenderMode mode) {
        this.currentRenderMode = mode;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (frameLock) {
            frameAvailable.set(true);
        }
        glSurfaceView.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        videoTextureId = createExternalOESTexture();
        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        videoSurfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(videoSurfaceTexture);

        depthMapTextureId = createEmptyTexture(modelInputWidth, modelInputHeight);

        simple3dProgram = createProgram(ShaderUtils.SIMPLE_VERTEX_SHADER, ShaderUtils.SIMPLE_FRAGMENT_SHADER);
        bilateralBlurProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_GAUSSIAN_BLUR);
        dibr3dProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_DUAL_BUBBLE_3D);

        initializeFilterFbo();
        initializeIntermediateFbo();
        initializeTfLite();
        initializeFbo();
        initBuffer();

        executorService = Executors.newSingleThreadExecutor();

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onSurfaceReady(videoSurface);
        }
    }

    // FÜGE DIESE NEUE METHODE ZU DEINER KLASSE HINZU:
    private void initializeIntermediateFbo() {
        intermediateTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        intermediateFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, intermediateTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("Stereo3DRenderer", "Intermediate Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }
    private void applyTwoPassGaussianBlur() {
        // Behalte den Namen deines Shader-Programms bei (z.B. bilateralBlurProgram)
        // B
        int blurProgram = bilateralBlurProgram;

        GLES20.glUseProgram(blurProgram);

        // Hole die Handles für die Uniforms und Attribute
        int posHandle = GLES20.glGetAttribLocation(blurProgram, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(blurProgram, "a_TexCoord");
        int inputTextureHandle = GLES20.glGetUniformLocation(blurProgram, "s_InputTexture");
        int texelSizeHandle = GLES20.glGetUniformLocation(blurProgram, "u_texelSize");
        int directionHandle = GLES20.glGetUniformLocation(blurProgram, "u_blurDirection");

        // Setze die Attribute (sind für beide Durchgänge gleich)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        // Setze die Texel-Größe (ist für beide Durchgänge gleich)
        GLES20.glUniform2f(texelSizeHandle, 1.0f / modelInputWidth, 1.0f / modelInputHeight);


        // --- 1. HORIZONTALER DURCHGANG (rohe Karte -> Zwischen-Textur) ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        // Setze die Richtung auf horizontal
        GLES20.glUniform2f(directionHandle, 1.0f, 0.0f);

        // Binde die rohe Tiefenkarte (depthMapTextureId) als Input
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
        GLES20.glUniform1i(inputTextureHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);


        // --- 2. VERTIKALER DURCHGANG (Zwischen-Textur -> finale gefilterte Textur) ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        // Setze die Richtung auf vertikal
        GLES20.glUniform2f(directionHandle, 0.0f, 1.0f);

        // Binde die ZWISCHEN-Textur (intermediateTextureId) als Input
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTextureId);
        GLES20.glUniform1i(inputTextureHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Setze den Framebuffer auf den Standard-Bildschirm zurück
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * Draws the scene with Depth-Image-Based Rendering (DIBR) for both eyes.
     * This method synthesizes both the left and right eye views from a single
     * color texture and a corresponding depth map.
     *
     * @param program The handle to the DIBR shader program.
     * @param parallax The base parallax factor, controlling the intensity of the 3D effect.
     * @param convergenceStrength A factor to adjust the dynamic convergence based on scene depth.
     */
    /**
     * Draws the scene with Depth-Image-Based Rendering (DIBR) for both eyes.
     * This method synthesizes both the left and right eye views from a single
     * color texture and a corresponding depth map.
     *
     * @param program The handle to the DIBR shader program.
     * @param parallax The base parallax factor, controlling the intensity of the 3D effect.
     * @param convergenceStrength A factor to adjust the dynamic convergence based on scene depth.
     */
    private void drawWithDibr(int program, float parallax, float convergenceStrength) {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        // --- 1. Common Setup for Both Eyes ---

        // Use the DIBR shader program for all subsequent rendering.
        GLES20.glUseProgram(program);

        // Get handles for shader attributes and uniforms.
        int posHandle = GLES20.glGetAttribLocation(program, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        int colorTexHandle = GLES20.glGetUniformLocation(program, "s_ColorTexture");
        int depthTexHandle = GLES20.glGetUniformLocation(program, "s_DepthTexture");
        int parallaxHandle = GLES20.glGetUniformLocation(program, "u_parallax");
        int convergenceHandle = GLES20.glGetUniformLocation(program, "u_convergence");
        int focusPointHandle = GLES20.glGetUniformLocation(program, "u_focusPoint");

        // Enable and set the vertex attribute arrays for position and texture coordinates.
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        // Calculate the dynamic convergence based on the average scene depth.
        // This helps adjust the focal point to reduce eye strain.
        float dynamicConvergence = (smoothedAverageSceneDepth) * (convergenceStrength / 10f);
        LimeLog.info("dynamicConvergence: " + dynamicConvergence);

        // Halve the parallax and convergence values to apply them to each eye individually.
        float halfParallax = parallax / 2.0f;
        float halfConvergence = dynamicConvergence / 2.0f;


        // Set the uniforms that are the same for both eyes (focus point).
        if (focusPointHandle != -1) {
            GLES20.glUniform2f(focusPointHandle, smoothedFocusPointX, smoothedFocusPointY);
        }

        // Bind the color texture (video frame) to texture unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glUniform1i(colorTexHandle, 0);

        // Bind the depth map texture to texture unit 1.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId);
        GLES20.glUniform1i(depthTexHandle, 1);


        // --- 2. Left Eye Rendering ---

        // Set the viewport to the left half of the screen.
        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);

        // Set the parallax uniform to a NEGATIVE value for the left eye.
        GLES20.glUniform1f(parallaxHandle, -halfParallax);

        // Set the convergence uniform. Assuming the shader subtracts the convergence value,
        // a negative value here will shift the image to the right.
        if (convergenceHandle != -1) {
            GLES20.glUniform1f(convergenceHandle, -halfConvergence);
        }

        // Draw the quad for the left eye.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);


        // --- 3. Right Eye Rendering ---

        // Set the viewport to the right half of the screen.
        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);

        // Set the parallax uniform to a POSITIVE value for the right eye.
        GLES20.glUniform1f(parallaxHandle, halfParallax);

        // Set the convergence uniform. A positive value will shift the image to the left.
        if (convergenceHandle != -1) {
            GLES20.glUniform1f(convergenceHandle, halfConvergence);
        }

        // Draw the quad for the right eye.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
    private float lockedTargetFocusPointX = 0.5f;
    private float lockedTargetFocusPointY = 0.5f;
    private float lockedTargetMinFocusPointX = 0.5f;
    private float lockedTargetMinFocusPointY = 0.5f;
    private float targetDepthContrast = 0.0f; // NEU
    private void drawWithDualBubbleDepth(int dualBubble3dProgram, float parallaxStrength, float convergenceStrength) {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        float parallax = 0.1f * (parallaxStrength * 1.5f);
        float convergence = convergenceStrength * 0.018f * smoothedAverageSceneDepth;

        // --- Left Eye (Positive Effects Only) ---
        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, -parallax, -convergence);

        // --- Right Eye (Negative Effects Only) ---
        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, parallax, convergence);
    }
    private Boolean isDebugMode = false;

    private void drawEye(int program, float parallax, float convergence) {
        GLES20.glUseProgram(program);

        int posHandle = GLES20.glGetAttribLocation(program, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        int colorTexHandle = GLES20.glGetUniformLocation(program, "s_ColorTexture");
        int depthTexHandle = GLES20.glGetUniformLocation(program, "s_DepthTexture");
        int parallaxHandle = GLES20.glGetUniformLocation(program, "u_parallax");
        int convergenceHandle = GLES20.glGetUniformLocation(program, "u_convergence");
        int focusPointHandle = GLES20.glGetUniformLocation(program, "u_focusPoint");
        int minFocusPointHandle = GLES20.glGetUniformLocation(program, "u_minFocusPoint");
        int debugModeHandle = GLES20.glGetUniformLocation(program, "u_debugMode");
        int bothEyesHandle = GLES20.glGetUniformLocation(program, "u_bothEyes");
        int depthContrastHandle = GLES20.glGetUniformLocation(program, "u_averageSceneDepth");

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        GLES20.glUniform1i(debugModeHandle, isDebugMode ? 1 : 0);
        GLES20.glUniform1i(bothEyesHandle, 0);
        GLES20.glUniform2f(focusPointHandle, smoothedFocusPointX, smoothedFocusPointY);
        GLES20.glUniform2f(minFocusPointHandle, smoothedMinFocusPointX, smoothedMinFocusPointY);
        GLES20.glUniform1f(depthContrastHandle, smoothedAverageSceneDepth);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glUniform1i(colorTexHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId);
        GLES20.glUniform1i(depthTexHandle, 1);

        GLES20.glUniform1f(parallaxHandle, parallax);
        GLES20.glUniform1f(convergenceHandle, convergence);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }


    private void applyBilateralFilter() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(bilateralBlurProgram);

        int posHandle = GLES20.glGetAttribLocation(bilateralBlurProgram, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(bilateralBlurProgram, "a_TexCoord");
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        int inputTextureHandle = GLES20.glGetUniformLocation(bilateralBlurProgram, "s_InputTexture");
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId); // Lese die rohe Karte
        GLES20.glUniform1i(inputTextureHandle, 0);

        int texelSizeHandle = GLES20.glGetUniformLocation(bilateralBlurProgram, "u_texelSize");
        GLES20.glUniform2f(texelSizeHandle, 1.0f / modelInputWidth, 1.0f / modelInputHeight);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); // Schreibe in die gefilterte Textur

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void drawWithDibrAiSubtleShader() {
        if(prefConf != null) {
            drawWithDualBubbleDepth(dibr3dProgram, prefConf.parallax_depth, prefConf.scenery_depth);
        }
    }

    private float normalParallax = 0.3f;
    private float normalConvergence = 1f;

    private void drawWithFake3DShader() {
        drawWithDualBubbleDepth(dibr3dProgram, normalParallax, normalConvergence);
    }

    private float strongParallax = 0.5f;
    private float strongConvergence = 0.5f;

    private void drawWithDibrAiStrongShader() {
        drawWithDualBubbleDepth(dibr3dProgram, strongParallax, strongConvergence);
    }

    private int intermediateTextureId; // Für das Ergebnis des ersten Blur-Durchgangs
    private int intermediateFboHandle;

    private void initBuffer() {
        if (tflite != null) {
            // Annahme: Input ist ein Float-Tensor [1, H, W, 3]
            int inputSize = 1 * modelInputHeight * modelInputWidth * 3; // 1*H*W*C*BytesPerFloat
            tfliteInputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());

            // Annahme: Output ist ein Float-Tensor [1, H, W, 1]
            int outputSize = 1 * modelInputHeight * modelInputWidth * 1; // 1*H*W*C*BytesPerFloat
            tfliteOutputBuffer = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder());
        }
    }
    private ByteBuffer latestUnsmoothedDepthMap = null;
    private int frameCount = 0;

    private final Object depthMapLock = new Object();

    private void smoothConvergence(float smoothingfactor) {
        smoothedAverageSceneDepth = (targetAverageSceneDepth * (1.0f - smoothingfactor)) + (smoothedAverageSceneDepth * smoothingfactor);
    }


    private float[] depthVelocity; // Speichert die "Geschwindigkeit" jedes Pixels für die Feder-Glättung

    private float[] smoothedDepthPosition; // Speichert die geglättete Position mit voller Präzision
    private void smoothAndUpdateDepthMap() {
        synchronized (depthMapLock) {
            if (latestUnsmoothedDepthMap == null) {
                return;
            }

            // Initialisiere alle Zustands-Arrays und Buffer beim ersten Durchlauf.
            if (smoothedDepthPosition == null) {
                int mapSize = latestUnsmoothedDepthMap.capacity();
                smoothedDepthPosition = new float[mapSize];
                depthVelocity = new float[mapSize];
                previousSmoothedDepthBytes = ByteBuffer.allocate(mapSize);

                latestUnsmoothedDepthMap.rewind();
                previousSmoothedDepthBytes.put(latestUnsmoothedDepthMap); // Fülle den Byte-Buffer
                latestUnsmoothedDepthMap.rewind();
                for (int i = 0; i < mapSize; i++) {
                    // Fülle den Float-Array
                    smoothedDepthPosition[i] = latestUnsmoothedDepthMap.get(i) & 0xFF;
                }
            }

            // Berechne die durchschnittliche Änderung (nutzt den Byte-Buffer, wie von dir gewünscht)
            long totalDifference = 0;
            latestUnsmoothedDepthMap.rewind();
            previousSmoothedDepthBytes.rewind();
            for (int i = 0; i < latestUnsmoothedDepthMap.capacity(); i++) {
                totalDifference += Math.abs((latestUnsmoothedDepthMap.get(i) & 0xFF) - (previousSmoothedDepthBytes.get(i) & 0xFF));
            }
            double averageDifference = (double) totalDifference / latestUnsmoothedDepthMap.capacity();

            // Deine adaptive Logik zur Bestimmung der Federstärke (bleibt gleich)
            final double SCENE_CUT_THRESHOLD = 80.0;
            float springStrength;
            if (averageDifference >= SCENE_CUT_THRESHOLD) {
                springStrength = 1.0f;
                Arrays.fill(depthVelocity, 0.0f);
            } else {
                final float MIN_SPRING = 0.0001f;
                final float MAX_SPRING_NORMAL = (prefConf != null) ? prefConf.transition_speed : 0.3f;
                double ratio = Math.min(averageDifference / SCENE_CUT_THRESHOLD, 1.0);
                ratio = Math.pow(ratio, 2.0);
                springStrength = (float) (MIN_SPRING + (MAX_SPRING_NORMAL - MIN_SPRING) * ratio);
            }
            float damping = 0.6f;
            LimeLog.info("SMOOTHITEST (Spring): " + springStrength + " (AvgDiff): " + averageDifference );

            // --- Finale Feder-Dämpfer-Logik ---
            ByteBuffer newSmoothedBytes = ByteBuffer.allocate(latestUnsmoothedDepthMap.capacity());
            latestUnsmoothedDepthMap.rewind();

            for (int i = 0; i < latestUnsmoothedDepthMap.capacity(); i++) {
                // Lese den präzisen alten Zustand aus dem float-Array
                float prevPos = smoothedDepthPosition[i];
                float currentTarget = latestUnsmoothedDepthMap.get(i) & 0xFF;
                float newPos;

                if (springStrength >= 1.0f) {
                    newPos = currentTarget;
                    depthVelocity[i] = 0;
                } else {
                    float distance = currentTarget - prevPos;
                    float springForce = distance * springStrength;
                    float currentVel = depthVelocity[i];
                    currentVel += springForce;
                    currentVel *= damping;
                    depthVelocity[i] = currentVel;
                    newPos = prevPos + currentVel;
                }

                // Speichere den neuen, präzisen Zustand im float-Array für die nächste Berechnung
                smoothedDepthPosition[i] = newPos;

                // Speichere eine gerundete Version im Byte-Buffer für die GPU und deine Vergleiche
                newSmoothedBytes.put((byte) Math.max(0, Math.min(255, newPos)));
            }

            newSmoothedBytes.rewind();
            uploadLatestDepthMapToGpu(newSmoothedBytes);
            smoothFocusPoint(1f - springStrength);

            // Aktualisiere den Byte-Buffer, damit er für den nächsten Vergleich bereit ist.
            this.previousSmoothedDepthBytes = newSmoothedBytes;
        }
    }


    private void smoothFocusPoint(float smoothingfactor) {
        float lerpFactor = 1.0f - smoothingfactor;
        smoothedFocusPointX = (targetFocusPointX * lerpFactor) + (smoothedFocusPointX * smoothingfactor);
        smoothedFocusPointY = (targetFocusPointY * lerpFactor) + (smoothedFocusPointY * smoothingfactor);
        smoothedMinFocusPointX = (targetMinFocusPointX * lerpFactor) + (smoothedMinFocusPointX * smoothingfactor);
        smoothedMinFocusPointY = (targetMinFocusPointY * lerpFactor) + (smoothedMinFocusPointY * smoothingfactor);
    }
    private float lockedTargetDepthContrast = 0.0f; // NEU
    @Override
    public void onDrawFrame(GL10 gl) {
        long startTime = System.nanoTime();
        if (gpuDelegateFailed.getAndSet(false)) {
            Log.w("Stereo3DRenderer", "GPU delegate failed at runtime. Re-initializing interpreter on CPU.");
            reinitializeTfLiteOnCpu();
        }

        synchronized (frameLock) {
            if (!frameAvailable.get()) {
                Log.w("Stereo3DRenderer", "No new frame available, skipping draw");
                return;
            }

            frameAvailable.set(false);
        }

        try {
            videoSurfaceTexture.updateTexImage();
        } catch (Exception e) {
            Log.w("Stereo3DRenderer", "updateTexImagse failed", e);
            return;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        DepthResult newResult = latestDepthResult.getAndSet(null);
        if (newResult != null) {
            synchronized (depthMapLock) {
                latestUnsmoothedDepthMap = newResult.depthMap;
            }

            final float FOCUS_CHANGE_THRESHOLD = 2.0f / 16.0f; // Schwellenwert (z.B. 2 Raster-Zonen)

            LimeLog.info("SMOOTHRESULT " + newResult.averageDepth + " " + newResult.minFocusPointX + " " + newResult.focusPointX);
            float distMax = (float) Math.hypot(newResult.focusPointX - lockedTargetFocusPointX,
                    newResult.focusPointY - lockedTargetFocusPointY);
            if (distMax > FOCUS_CHANGE_THRESHOLD) {
                lockedTargetFocusPointX = newResult.focusPointX;
                lockedTargetFocusPointY = newResult.focusPointY;
            }

            final float CONTRAST_CHANGE_THRESHOLD = 0.1f; // Schwellenwert für Kontraständerung

            if (Math.abs(newResult.averageDepth - lockedTargetDepthContrast) > CONTRAST_CHANGE_THRESHOLD) {
                lockedTargetDepthContrast = newResult.averageDepth; // Kontrast immer aktualisieren
            }

            float distMin = (float) Math.hypot(newResult.minFocusPointX - lockedTargetMinFocusPointX,
                    newResult.minFocusPointY - lockedTargetMinFocusPointY);
            if (distMin > FOCUS_CHANGE_THRESHOLD) {
                lockedTargetMinFocusPointX = newResult.minFocusPointX;
                lockedTargetMinFocusPointY = newResult.minFocusPointY;
            }


            targetAverageSceneDepth = lockedTargetDepthContrast;
            targetFocusPointX = lockedTargetFocusPointX;
            targetFocusPointY = lockedTargetFocusPointY;
            targetMinFocusPointX = lockedTargetMinFocusPointX;
            targetMinFocusPointY = lockedTargetMinFocusPointY;
            targetDepthContrast = lockedTargetDepthContrast;
        }

        // Smooth the convergence value and the depth map in every frame
        smoothAndUpdateDepthMap();
        boolean pixelWereCount = false;
        if (tflite != null) {
            if (!isAiRunning.get()) {
              //  if (frameCount == 0 || frameCount > 8) {
                    ByteBuffer pixelBuffer = readPixelsForAI();
                    pixelWereCount = true;
                    double sceneDifference = hasFrameChangedSignificantly(pixelBuffer);
                    LimeLog.info("DIFFERENCE " +sceneDifference);
                    if (sceneDifference > 5f) {
                       /* if(sceneDifference > 50f) {
                            LimeLog.info("DIFFERENCE RESET" +sceneDifference);
                            // Setze BEIDE Karten (die aktuelle und die für die Glättung) sofort zurück.
                            // Das verhindert, dass von der alten Szene zur neuen geglättet wird.
                            synchronized (depthMapLock) {
                                latestUnsmoothedDepthMap = flatDepthMap;
                                previousSmoothedDepthBytes = flatDepthMap;
                            }
                        }*/
                        previousPixelBufferForDiff = pixelBuffer;
                        isAiRunning.set(true);
                        executorService.submit(new DepthEstimationTask(pixelBuffer, sceneDifference));
                        if (frameCount > 8) frameCount = 0;
                    }
            //    }
            }
            applyTwoPassGaussianBlur();
            drawWithShader();
            frameCount++;
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1000000;
            Log.d("Stereo3DRenderer", "Total onDrawFrame time: " + durationMs + " ms " + pixelWereCount + "  " + frameCount);
        }
    }
    private ByteBuffer flatDepthMap = createFlatDepthMap();
    private ByteBuffer createFlatDepthMap() {
        int mapSize = modelInputWidth * modelInputHeight;
        byte[] flatData = new byte[mapSize];

        // Fülle das Array mit dem neutralen Wert 128 (entspricht 0.5f im Shader)
        Arrays.fill(flatData, (byte) 128);

        ByteBuffer flatMap = ByteBuffer.allocate(mapSize);
        flatMap.put(flatData);
        flatMap.rewind();
        return flatMap;
    }
    private ByteBuffer previousPixelBufferForDiff = null;

    /**
     * Compares the new pixel buffer to the last analyzed one to decide if a new AI inference is needed.
     *
     * @param newPixelBuffer The pixel data of the current frame.
     * @return True if the frame has changed significantly, false otherwise.
     */
    private double hasFrameChangedSignificantly(ByteBuffer newPixelBuffer){
        if (previousPixelBufferForDiff == null) {
            previousPixelBufferForDiff = newPixelBuffer;
            return 0f; // Always process the first frame
        }

        long totalDifference = 0;
        newPixelBuffer.rewind();
        previousPixelBufferForDiff.rewind();

        for (int i = 0; i < newPixelBuffer.capacity(); i++) {
            totalDifference += Math.abs((newPixelBuffer.get(i) & 0xFF) - (previousPixelBufferForDiff.get(i) & 0xFF));
        }

        return (double) totalDifference / newPixelBuffer.capacity();
    }

    private void uploadLatestDepthMapToGpu(ByteBuffer depthMap) {
        if (depthMap != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, modelInputWidth, modelInputHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, depthMap);
        }
    }

    private ByteBuffer readPixelsForAI() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        drawQuad(simple3dProgram, 1.0f, 0.0f);

        ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(modelInputWidth * modelInputHeight * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, modelInputWidth, modelInputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return pixelBuffer;
    }

    private void drawWithShader() {
        switch (currentRenderMode) {
            case MODE_AI_3D_STRONG:
                drawWithDibrAiStrongShader();
                break;
            case MODE_AI_3D:
                drawWithFake3DShader();
                break;
            case MODE_FASTPATH_3D:
                drawWithDibrAiSubtleShader();
                break;
            case MODE_2D:
            default:
                draw2DShader();
                break;
        }
    }

    private void draw2DShader() {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);
    }

    private void drawQuad(int program, float scale, float offset) {
        GLES20.glUseProgram(program);

        int posHandle = GLES20.glGetAttribLocation(program, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        int offsetHandle = GLES20.glGetUniformLocation(program, "u_xOffset");
        int scaleHandle = GLES20.glGetUniformLocation(program, "u_xScale");

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);

        if (scaleHandle != -1) GLES20.glUniform1f(scaleHandle, scale);
        if (offsetHandle != -1) GLES20.glUniform1f(offsetHandle, offset);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private ByteBuffer tfliteInputBuffer;
    private ByteBuffer tfliteOutputBuffer;

    private ByteBuffer previousSmoothedDepthBytes = null;
    private class DepthEstimationTask implements Runnable {
        private final ByteBuffer pixelBuffer;
        private final double imageDifference;

        DepthEstimationTask(ByteBuffer pixelBuffer, double imageDifference) {
            this.pixelBuffer = pixelBuffer;
            this.imageDifference = imageDifference;
        }

        @Override
        public void run() {
            long startTime = System.nanoTime();
            try {
                // Überprüfen, ob die Member-Variablen-Buffer initialisiert sind.
                if (tflite == null || pixelBuffer == null || tfliteInputBuffer == null || tfliteOutputBuffer == null) {
                    return;
                }

                // Die Member-Variablen-Buffer zurücksetzen, anstatt neue zu erstellen.
                tfliteInputBuffer.rewind();
                tfliteOutputBuffer.rewind();

                // KORREKTE BEFÜLL-SCHLEIFE (mit .put() statt .putFloat())
                pixelBuffer.rewind();
                for (int i = 0; i < modelInputWidth * modelInputHeight; i++) {
                    byte r = pixelBuffer.get();
                    byte g = pixelBuffer.get();
                    byte b = pixelBuffer.get();
                    pixelBuffer.get(); // Alpha überspringen

                    tfliteInputBuffer.put(r);
                    tfliteInputBuffer.put(g);
                    tfliteInputBuffer.put(b);
                }
                tfliteInputBuffer.rewind();

                // 2. Führe die KI-Inferenz aus
                tflite.run(tfliteInputBuffer, tfliteOutputBuffer);
                // KORREKTE UINT8-NORMALISIERUNG
                tfliteOutputBuffer.rewind();

// 1. Finde Min/Max als Integer im Bereich 0-255
                int min = 255;
                int max = 0;
                for (int i = 0; i < tfliteOutputBuffer.capacity(); i++) {
                    int val = tfliteOutputBuffer.get(i) & 0xFF; // Lese als unsigned byte
                    if (val < min) min = val;
                    if (val > max) max = val;
                }
                int range = max - min;

// 2. Erstelle die finale, kontrastreiche Map
                ByteBuffer depthBytes = ByteBuffer.allocateDirect(modelInputWidth * modelInputHeight);
                tfliteOutputBuffer.rewind();

                if (range > 0) {
                    for (int i = 0; i < tfliteOutputBuffer.capacity(); i++) {
                        int val = tfliteOutputBuffer.get(i) & 0xFF;
                        // Normalisiere den Wert und strecke den Kontrast auf den vollen Bereich
                        int stretchedValue = ((val - min) * 255) / range;
                        depthBytes.put((byte) stretchedValue);
                    }
                } else {
                    // Falls kein Kontrast vorhanden ist (flaches Bild), kopiere einfach die Rohdaten
                    depthBytes.put(tfliteOutputBuffer);
                }
                depthBytes.rewind();

                final double INSTABILITY_THRESHOLD = 10.0;
                final double MIN_IMAGE_DIFFERENCE = 1.0; // Ignoriere winzige Bildänderungen, um Instabilität zu vermeiden

                if (previousSmoothedDepthBytes != null) {
                    // 1. Berechne die Änderung der Tiefenkarte (wie bisher)
                    long totalDepthDifference = 0;
                    depthBytes.rewind();
                    previousSmoothedDepthBytes.rewind();
                    for (int i = 0; i < depthBytes.capacity(); i++) {
                        totalDepthDifference += Math.abs((depthBytes.get(i) & 0xFF) - (previousSmoothedDepthBytes.get(i) & 0xFF));
                    }
                    double averageDepthDifference = (double) totalDepthDifference / depthBytes.capacity();
                    depthBytes.rewind();
                    previousSmoothedDepthBytes.rewind();

                    // 2. Berechne den Instabilitäts-Faktor
                    // Teile die Tiefenänderung durch die Bildänderung.
                    // Ein kleiner Puffer (MIN_IMAGE_DIFFERENCE) verhindert eine Division durch Null
                    // und ignoriert winziges Bildrauschen.
                    double instabilityFactor = averageDepthDifference / Math.max(this.imageDifference, MIN_IMAGE_DIFFERENCE);

                    // 3. Verwerfe die Karte, wenn der Faktor zu hoch ist
                    // Das bedeutet: "Die Tiefe hat sich VIEL MEHR geändert als das Bild selbst."
                    if (instabilityFactor > INSTABILITY_THRESHOLD) {
                        LimeLog.info("Unstable AI result ignored. Instability Factor: " + instabilityFactor +
                                " (DepthDiff: " + averageDepthDifference + ", ImageDiff: " + this.imageDifference + ")");
                        return; // Verwirf dieses Ergebnis
                    }
                }

                // --- Calculate the new focus point ---
                final int NUM_REGIONS = 16;
                long[][] regionSums = new long[NUM_REGIONS][NUM_REGIONS];
                int[][] regionCounts = new int[NUM_REGIONS][NUM_REGIONS];
                int regionWidth = modelInputWidth / NUM_REGIONS;
                int regionHeight = modelInputHeight / NUM_REGIONS;

                depthBytes.rewind();
                for (int y = 0; y < modelInputHeight; y++) {
                    for (int x = 0; x < modelInputWidth; x++) {
                        int regionX = x / regionWidth;
                        int regionY = y / regionHeight;
                        if (regionX < NUM_REGIONS && regionY < NUM_REGIONS) {
                            regionSums[regionY][regionX] += (depthBytes.get() & 0xFF);
                            regionCounts[regionY][regionX]++;
                        }
                    }
                }
                depthBytes.rewind();

                int maxRegionX = 0, maxRegionY = 0;
                int minRegionX = 0, minRegionY = 0;
                long maxSum = -1;
                long minSum = Long.MAX_VALUE;

                for (int y = 0; y < NUM_REGIONS; y++) {
                    for (int x = 0; x < NUM_REGIONS; x++) {
                        long currentSum = regionSums[y][x];
                        if (currentSum > maxSum) {
                            maxSum = currentSum;
                            maxRegionX = x;
                            maxRegionY = y;
                        }
                        if (currentSum < minSum) {
                            minSum = currentSum;
                            minRegionX = x;
                            minRegionY = y;
                        }
                    }
                }

                float newFocusPointX = (maxRegionX + 0.5f) / NUM_REGIONS;
                float newFocusPointY = (maxRegionY + 0.5f) / NUM_REGIONS;
                float newMinFocusPointX = (minRegionX + 0.5f) / NUM_REGIONS;
                float newMinFocusPointY = (minRegionY + 0.5f) / NUM_REGIONS;
                float maxDepth = (float)(regionSums[maxRegionY][maxRegionX] / (double)regionCounts[maxRegionY][maxRegionX]) / 255.0f;
                float minDepth = (float)(regionSums[minRegionY][minRegionX] / (double)regionCounts[minRegionY][minRegionX]) / 255.0f;
                float depthContrast = maxDepth - minDepth;
                latestDepthResult.set(new DepthResult(depthBytes, depthContrast, newFocusPointX, newFocusPointY, newMinFocusPointX, newMinFocusPointY));

            } catch (Exception e) {
                Log.e("DepthEstimationTask", "AI inference failed", e);
                gpuDelegateFailed.set(true);
            } finally {
                isAiRunning.set(false);
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                Log.d("Stereo3DRenderer", "Total DepthEstimationTask time: " + duration + " ms");
            }
        }
    }

    private void initializeTfLite() {

        Interpreter.Options options = new Interpreter.Options();

        // 1️⃣ NnApiDelegate
        try {
            GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
            gpuOptions.setQuantizedModelsAllowed(true); // Optional: je nach Bedarf
            gpuOptions.setPrecisionLossAllowed(true);
            gpuOptions.setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
            GpuDelegate gpuDelegate = new GpuDelegate(gpuOptions);
            options.addDelegate(gpuDelegate);
            System.out.println("MYLOG GPU Delegate aktiviert");
            tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
        } catch (Exception e) {
            System.out.println("MYLOG GPU Delegate nicht verfügbar: " + e.getMessage());
            // 2️⃣ GPU Delegate
            try {
                NnApiDelegate nnapi = new NnApiDelegate();
                options.addDelegate(nnapi);
                System.out.println("MYLOG NNAPI Delegate aktiviert");
                tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);

            } catch (Exception exception) {
                System.out.println("MYLOG NNAPI Delegate nicht verfügbar: " + e.getMessage());
                // 3️⃣ CPU Fallback
                try {
                    System.out.println("MYLOG Fallback: CPU");
                    tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
                } catch (Exception ex) {
                    reinitializeTfLiteOnCpu();
                }
            }
        }
    }

    private void reinitializeTfLiteOnCpu() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }

        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setUseNNAPI(true);
            options.setNumThreads(4);
            tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
            Log.i("Stereo3DRenderer", "Successfully re-initialized TFLite interpreter on CPU.");
        } catch (IOException e) {
            Log.e("Stereo3DRenderer", "Failed to re-initialize TFLite model on CPU.", e);
        }
    }

    private String AI_MODEL = "midas-midas-v2-w8a8.tflite";

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws
            IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    private void initializeFbo() {
        fboTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        fboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("Stereo3DRenderer", "Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void initializeFilterFbo() {
        // --- FINALE KORREKTUR: Verwende createRgbaTexture, um Kompatibilität sicherzustellen ---
        filteredDepthMapTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        filterFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("Stereo3DRenderer", "Filter Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private int createExternalOESTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        return textureId;
    }

    private int createEmptyTexture(int width, int height) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        return textureId;
    }

    private int createRgbaTexture(int width, int height) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        return textureId;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Stereo3DRenderer", "Could not compile shader " + type + ":");
            Log.e("Stereo3DRenderer", GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private int createProgram(String vertex, String fragment) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        if (vertexShader == 0) return 0;
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (fragmentShader == 0) return 0;

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e("Stereo3DRenderer", "Could not link program: ");
                Log.e("Stereo3DRenderer", GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }
}
