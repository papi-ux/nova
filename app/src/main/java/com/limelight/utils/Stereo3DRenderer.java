package com.limelight.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import org.opencv.android.OpenCVLoader;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
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

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private static final float[] TEXTURE_VERTICES = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;

    private final GLSurfaceView glSurfaceView;
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    private final Context context;

    private SurfaceTexture videoSurfaceTexture;


    private final float smoothedAverageSceneDepth = 0.0f;
    private Surface videoSurface;
    private int videoTextureId;
    private int depthMapTextureId;
    private int fboHandle;
    private int fboTextureId;

    private int simple3dProgram;

    private int bilateralBlurProgram;

    private int dibr3dProgram;

    private Interpreter tflite;
    private GpuDelegate gpuDelegate;
    private final int modelInputWidth = 256;
    private final int modelInputHeight = 256;

    private ExecutorService executorService;

    // Variables for dynamic convergence

    private int filteredDepthMapTextureId; // Smoothed version of the depth map
    private int filterFboHandle; // FBO for the blur pass
    private final Object frameLock = new Object();
    private PreferenceConfiguration prefConf;
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);
    private final AtomicBoolean isSmoothingRunning = new AtomicBoolean(false);
    private final AtomicBoolean gpuDelegateFailed = new AtomicBoolean(false);

    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);

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
        initializeDownsampleFbo();
        initializePBOs();
        initBuffer();

        int pboSize = modelInputWidth * modelInputHeight * 4;
        previousPixelBuffer = ByteBuffer.allocateDirect(pboSize).order(ByteOrder.nativeOrder());

        executorService = Executors.newFixedThreadPool(2);

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onSurfaceReady(videoSurface);
        }
    }

    /**
     * Führt eine performante Szenenänderungs-Erkennung auf einer kleinen
     * heruntergerechneten Version des Videobildes durch.
     *
     * @return Der berechnete Unterschiedswert (z.B. average pixel difference).
     */
    private double detectSceneChange() {

        // 1. Zeichne das große Videobild in die kleine Downsample-Textur.
        //    Die GPU erledigt die Skalierung automatisch und blitzschnell.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downsampleFboHandle);
        GLES20.glViewport(0, 0, DOWNSAMPLE_SIZE, DOWNSAMPLE_SIZE);
        drawQuad(simple3dProgram, 1.0f, 0.0f); // Zeichne das Video-Quad

        // 2. Lese NUR die kleine 16x16 Textur zurück zur CPU. Das ist extrem schnell.
        downsamplePixelBuffer.rewind();
        GLES20.glReadPixels(0, 0, DOWNSAMPLE_SIZE, DOWNSAMPLE_SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, downsamplePixelBuffer);

        // Setze den Framebuffer zurück, damit wieder auf den Bildschirm gezeichnet wird
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // 3. Vergleiche den aktuellen kleinen Buffer mit dem vom letzten Frame
        double difference = 0.0;
        if (previousDownsamplePixelBuffer != null) {
            difference = hasFrameChangedSignificantly(downsamplePixelBuffer, previousDownsamplePixelBuffer);
        }

        // 4. Speichere den aktuellen kleinen Buffer für den nächsten Frame
        if (previousDownsamplePixelBuffer == null) {
            previousDownsamplePixelBuffer = ByteBuffer.allocateDirect(downsamplePixelBuffer.capacity());
        }
        previousDownsamplePixelBuffer.rewind();
        downsamplePixelBuffer.rewind();
        previousDownsamplePixelBuffer.put(downsamplePixelBuffer);
        return difference;
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

    private void drawWithDualBubbleDepth(int dualBubble3dProgram, float parallaxStrength, float convergenceStrength) {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        float parallax = 0.1f * (parallaxStrength * 2f);
        float convergence = convergenceStrength * 0.018f * smoothedAverageSceneDepth;

        // --- Left Eye (Positive Effects Only) ---
        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, -parallax, -convergence);

        // --- Right Eye (Negative Effects Only) ---
        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, parallax, convergence);
    }

    public static Boolean isDebugMode = false;

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
        GLES20.glUniform2f(focusPointHandle, 0f, 0f);
        GLES20.glUniform2f(minFocusPointHandle, 0f, 0f);
        GLES20.glUniform1f(depthContrastHandle, 0f);

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

    private void drawWithShader() {
        if (prefConf != null) {
            drawWithDualBubbleDepth(dibr3dProgram, prefConf.parallax_depth, 0f);
        }
    }

    private int intermediateTextureId; // Für das Ergebnis des ersten Blur-Durchgangs
    private int intermediateFboHandle;

    private void initBuffer() {
        if (tflite != null) {
            // Annahme: Input ist ein Float-Tensor [1, H, W, 3]
            int inputSize = modelInputHeight * modelInputWidth * 3; // 1*H*W*C*BytesPerFloat
            tfliteInputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());

            // Annahme: Output ist ein Float-Tensor [1, H, W, 1]
            int outputSize = modelInputHeight * modelInputWidth; // 1*H*W*C*BytesPerFloat
            tfliteOutputBuffer = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder());
        }
    }

    private ByteBuffer latestUnsmoothedDepthMap = createFlatDepthMap();
    private final int frameCount = 0;

    private final Object depthMapLock = new Object();

    private float[] depthVelocity; // Speichert die "Geschwindigkeit" jedes Pixels für die Feder-Glättung

    private float[] smoothedDepthPosition; // Speichert die geglättete Position mit voller Präzision

    private final AtomicReference<ByteBuffer> newSmoothedMapAvailable = new AtomicReference<>();

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

        ByteBuffer smoothedMap = newSmoothedMapAvailable.getAndSet(null);
        if (smoothedMap != null) {
            this.previousSmoothedDepthBytes = smoothedMap;
        }
        try {
            videoSurfaceTexture.updateTexImage();
        } catch (Exception e) {
            Log.w("Stereo3DRenderer", "updateTexImagse failed", e);
            return;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        boolean pixelWereCount = false;
        if (tflite != null) {
            if (previousSmoothedDepthBytes != null && !isSmoothingRunning.get()) {
                isSmoothingRunning.set(true);
                executorService.submit(new SmoothDepthmapTask());
            }
            double sceneDifference = detectSceneChange();
           
            if (sceneDifference >= 1f || sceneChanged) {
                if (!isAiRunning.get()) {
                    isAiRunning.set(true);
                    sceneChanged = false;
                    executorService.submit(new DepthEstimationTask(readPixelsForAI()));
                } else {
                    sceneChanged = true;
                }
            }
            uploadLatestDepthMapToGpu(previousSmoothedDepthBytes);
            applyTwoPassGaussianBlur();
            drawWithShader();
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1000000;
            Log.d("Stereo3DRenderer", "Total onDrawFrame time: " + durationMs + " ms " + pixelWereCount + "  " + frameCount);
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

    private boolean sceneChanged = false;

    private final ByteBuffer flatDepthMap = createFlatDepthMap();

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


    /**
     * Compares the new pixel buffer to the last analyzed one to decide if a new AI inference is needed.
     *
     * @param newPixelBuffer The pixel data of the current frame.
     * @return True if the frame has changed significantly, false otherwise.
     */
    private double hasFrameChangedSignificantly(ByteBuffer newPixelBuffer, ByteBuffer oldPixelBuffer) {
        if (oldPixelBuffer == null) return 0.0;

        long totalDifference = 0;
        newPixelBuffer.rewind();
        oldPixelBuffer.rewind();

        for (int i = 0; i < newPixelBuffer.capacity(); i++) {
            totalDifference += Math.abs((newPixelBuffer.get(i) & 0xFF) - (oldPixelBuffer.get(i) & 0xFF));
        }

        return Math.max((double) totalDifference / newPixelBuffer.capacity() - 6, 0);
    }

    private void uploadLatestDepthMapToGpu(ByteBuffer depthMap) {
        if (depthMap != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, modelInputWidth, modelInputHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, depthMap);
        }
    }

    private static final int DOWNSAMPLE_SIZE = 16; // Eine 16x16px Miniatur
    private int downsampleFboHandle;
    private int downsampleTextureId;
    private ByteBuffer downsamplePixelBuffer;
    private ByteBuffer previousDownsamplePixelBuffer;

    private ByteBuffer previousPixelBuffer;

    // Rufe diese Methode in onSurfaceCreated() auf
    private void initializeDownsampleFbo() {
        downsampleTextureId = createRgbaTexture(DOWNSAMPLE_SIZE, DOWNSAMPLE_SIZE);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        downsampleFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downsampleFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, downsampleTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("Stereo3DRenderer", "Downsample Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // Initialisiere die Buffer für den CPU-Vergleich
        downsamplePixelBuffer = ByteBuffer.allocateDirect(DOWNSAMPLE_SIZE * DOWNSAMPLE_SIZE * 4).order(ByteOrder.nativeOrder());
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

    private ByteBuffer previousSmoothedDepthBytes = flatDepthMap;

    private final int[] pboHandles = new int[2];
    private int pboIndex = 0;
    private int PBO_SIZE = modelInputWidth * modelInputHeight * 4;

    private class SmoothDepthmapTask implements Runnable {

        SmoothDepthmapTask() {
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                try {
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
                        double instabilityFactor = averageDifference / Math.max(latestSceneDifference, 1f);
                        final double SCENE_CUT_THRESHOLD = 80.0;
                        float springStrength;
                        float damping = 0.6f;
                        if (averageDifference >= SCENE_CUT_THRESHOLD || instabilityFactor > 10f) {
                            springStrength = 1.0f;
                            Arrays.fill(depthVelocity, 0.0f);
                            damping = 1f;
                        } else {
                            final float MIN_SPRING = 0f;
                            final float MAX_SPRING_NORMAL = (float) instabilityFactor / 100f;
                            double ratio = Math.min(averageDifference / SCENE_CUT_THRESHOLD, 1.0);
                            ratio = Math.pow(ratio, 2.0);
                            springStrength = (float) (MIN_SPRING + (MAX_SPRING_NORMAL - MIN_SPRING) * ratio);
                        }
                        
                        if (springStrength < 0.01) {
                            break;
                        }
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
                        newSmoothedMapAvailable.set(newSmoothedBytes);

                    }
                } catch (Exception e) {
                } finally {
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    Log.d("Stereo3DRenderer", "Total Smoothing time: " + duration + " ms");
                }
            }
            isSmoothingRunning.set(false);
        }
    }

    private class DepthEstimationTask implements Runnable {
        private final ByteBuffer pixelBuffer;

        DepthEstimationTask(ByteBuffer pixelBuffer) {
            this.pixelBuffer = pixelBuffer;
        }

        @Override
        public void run() {
            long startTime = System.nanoTime();
            try {
                // Überprüfen, ob die Member-Variablen-Buffer initialisiert sind.
                if (tflite == null || pixelBuffer == null || tfliteInputBuffer == null || tfliteOutputBuffer == null) {
                    return;
                }
                ByteBuffer depthBytes = ByteBuffer.allocateDirect(modelInputWidth * modelInputHeight);
                // Die Member-Variablen-Buffer zurücksetzen, anstatt neue zu erstellen.
                tfliteInputBuffer.rewind();
                tfliteOutputBuffer.rewind();

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
                double imageDifference = 0.0f;
                int min = 255;
                int max = 0;
                for (int i = 0; i < tfliteOutputBuffer.capacity(); i++) {
                    int val = tfliteOutputBuffer.get(i) & 0xFF; // Lese als unsigned byte
                    if (val < min) min = val;
                    if (val > max) max = val;
                }
                int range = max - min;

// 2. Erstelle die finale, kontrastreiche Map
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
                    if (previousPixelBuffer != null) {
                        imageDifference = hasFrameChangedSignificantly(pixelBuffer, previousPixelBuffer);
                        previousPixelBuffer.rewind();
                        previousPixelBuffer.put(pixelBuffer);
                        if (imageDifference < MIN_IMAGE_DIFFERENCE) {
                            return;
                        }
                    }
                    double instabilityFactor = averageDepthDifference / Math.max(imageDifference, MIN_IMAGE_DIFFERENCE);

                    // 3. Verwerfe die Karte, wenn der Faktor zu hoch ist
                    // Das bedeutet: "Die Tiefe hat sich VIEL MEHR geändert als das Bild selbst."
                    if (instabilityFactor > INSTABILITY_THRESHOLD) {
                        LimeLog.info("Unstable AI result ignored. Instability Factor: " + instabilityFactor +
                                " (DepthDiff: " + averageDepthDifference + ", ImageDiff: " + imageDifference + ")");
                        return; // Verwirf dieses Ergebnis
                    }
                }
                //ByteBuffer newTest = mixDepthMaps(previousSmoothedDepthBytes, depthBytes, 0.5f);
                synchronized (depthMapLock) {
                    latestUnsmoothedDepthMap = depthBytes;
                    latestSceneDifference = imageDifference;
                }

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

    private double latestSceneDifference = 0.0f;

    static {
        // This block is executed once when the class is first loaded.
        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Internal OpenCV library not found. Using OpenCV Manager for initialization");
        } else {
            Log.d("OpenCV", "OpenCV library found inside package. Using it!");
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
            LimeLog.info("GPU Delegate aktiviert");
            tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
        } catch (Exception e) {
            LimeLog.info("GPU Delegate nicht verfügbar: " + e.getMessage());
            // 2️⃣ GPU Delegate
            try {
                NnApiDelegate nnapi = new NnApiDelegate();
                options.addDelegate(nnapi);
                LimeLog.info("NNAPI Delegate aktiviert");
                tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);

            } catch (Exception exception) {
                LimeLog.info("NNAPI Delegate nicht verfügbar: " + e.getMessage());
                // 3️⃣ CPU Fallback
                try {
                    LimeLog.info("Fallback: CPU");
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

    private final String AI_MODEL = "midas-midas-v2-w8a8.tflite";

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

    private void initializePBOs() {
        // Berechne die Größe des Buffers, den wir auslesen wollen
        PBO_SIZE = modelInputWidth * modelInputHeight * 4; // RGBA

        // Erzeuge zwei Puffer-Objekte auf der GPU
        GLES30.glGenBuffers(2, pboHandles, 0);

        // Konfiguriere Puffer 0
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[0]);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ);

        // Konfiguriere Puffer 1
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[1]);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ);

        // Hebe die Bindung auf
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
    }

    /**
     * Liest die Pixel des FBOs asynchron aus, ohne die GPU-Pipeline zu blockieren.
     * Verwendet zwei Pixel Buffer Objects (PBOs) in einer Ping-Pong-Konfiguration.
     *
     * @return Ein ByteBuffer mit den Pixeldaten des VORHERIGEN Frames, oder null beim ersten Frame.
     */
    private ByteBuffer readPixelsForAI_Async() {
        // Zeichne das aktuelle Videobild in den FBO, aus dem wir lesen wollen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);

        // Bestimme, welcher Puffer zum Schreiben (aktueller Frame) und welcher zum Lesen (vorheriger Frame) dient
        int writeIndex = pboIndex;
        int readIndex = (pboIndex + 1) % 2;

        // --- 1. ASYNCHRONER SCHREIBBEFEHL AN DIE GPU ---
        // Binde den Puffer für den Schreibvorgang
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[writeIndex]);

        // Gib den Lesebefehl. Der letzte Parameter '0' weist OpenGL an, in den gebundenen PBO zu schreiben.
        // Dieser Aufruf kehrt SOFORT zurück und blockiert nicht!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            GLES30.glReadPixels(0, 0, modelInputWidth, modelInputHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0);
        }

        // --- 2. SYNCHRONER LESEZUGRIFF AUF DATEN DES LETZTEN FRAMES ---
        // Binde den Puffer des letzten Frames, der jetzt garantiert fertig kopiert ist.
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[readIndex]);

        // "Mappe" den GPU-Speicher in einen für die CPU lesbaren ByteBuffer.
        ByteBuffer pixelBuffer = (ByteBuffer) GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER, 0, PBO_SIZE, GLES30.GL_MAP_READ_BIT);

        // Wenn das Mapping erfolgreich war, haben wir unsere Daten.
        if (pixelBuffer != null) {
            // WICHTIG: Hebe das Mapping sofort wieder auf, damit die GPU den Puffer wiederverwenden kann.
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
        }

        // Hebe alle Bindungen auf, um den Zustand sauber zu halten.
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // Wechsle zum nächsten Puffer für den nächsten Frame
        pboIndex = readIndex;

        // Gib den Buffer zurück. Achtung: Im ersten Frame ist dieser 'null',
        // da noch keine Daten vom "vorherigen" Frame vorhanden sind.
        return pixelBuffer;
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
