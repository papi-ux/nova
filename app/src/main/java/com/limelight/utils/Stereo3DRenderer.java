package com.limelight.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
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
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    static {
        // This block is executed once when the class is first loaded.
        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Internal OpenCV library not found. Using OpenCV Manager for initialization");
        } else {
            Log.d("OpenCV", "OpenCV library found inside package. Using it!");
        }
    }

    // Constants
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private static final float[] TEXTURE_VERTICES = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    public static Boolean isDebugMode = false;

    // Final Member Variables (Dependencies & Buffers)
    private final GLSurfaceView glSurfaceView;
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    private final Context context;
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;
    private final Object frameLock = new Object();

    // OpenGL Handles
    private int videoTextureId;
    private int depthMapTextureId;
    private int filteredDepthMapTextureId;
    private int fboHandle;
    private int fboTextureId;

    private int filterFboHandle;
    private int simple3dProgram;
    private int bilateralBlurProgram;
    private int dibr3dProgram;


    private final int NUM_INPUT_BUFFERS = 10;
    private BlockingQueue<ByteBuffer> freeInputBuffers;
    private final int NUM_SMOOTHED_BUFFERS = 3;
    private BlockingQueue<ByteBuffer> freeSmoothedBuffers;
    private BlockingQueue<ByteBuffer> readyToRenderQueue;

    // The single buffer that the GPU is currently reading from
    private ByteBuffer currentlyRenderingMap;
    // AI & TFLite Variables
    private Interpreter tflite;
    private GpuDelegate gpuDelegate;
    private final int modelInputWidth = 256;
    private final int modelInputHeight = 256;
    private ByteBuffer tfliteInputBuffer;

    // State & Logic Variables
    private Surface videoSurface;
    private SurfaceTexture videoSurfaceTexture;
    private PreferenceConfiguration prefConf;
    private ByteBuffer previousPixelBuffer;
    private ExecutorService executorService;
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);
    private final AtomicBoolean isAiResultHandlingRunning = new AtomicBoolean(false);
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
        initBuffer();

        int mapSize = modelInputWidth * modelInputHeight;
        freeSmoothedBuffers = new ArrayBlockingQueue<>(NUM_SMOOTHED_BUFFERS);
        readyToRenderQueue = new ArrayBlockingQueue<>(NUM_SMOOTHED_BUFFERS);
        for (int i = 0; i < NUM_SMOOTHED_BUFFERS; i++) {
            freeSmoothedBuffers.offer(ByteBuffer.allocateDirect(mapSize).order(ByteOrder.nativeOrder()));
        }

        int pboSize = modelInputWidth * modelInputHeight * 4;
        previousPixelBuffer = ByteBuffer.allocateDirect(pboSize).order(ByteOrder.nativeOrder());
        int inputPixelSize = modelInputWidth * modelInputHeight * 4;
        freeInputBuffers = new ArrayBlockingQueue<>(NUM_INPUT_BUFFERS);
        inferenceInputQueue = new ArrayBlockingQueue<>(1); // This queue still only needs one slot
        for (int i = 0; i < NUM_INPUT_BUFFERS; i++) {
            freeInputBuffers.offer(ByteBuffer.allocateDirect(inputPixelSize).order(ByteOrder.nativeOrder()));
        }

        executorService = Executors.newFixedThreadPool(2);

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onSurfaceReady(videoSurface);
        }
    }

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

    private void drawBothEyes(int dualBubble3dProgram, float parallaxStrength) {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        float parallax = 0.1f * (parallaxStrength * 2f);

        // --- Left Eye (Positive Effects Only) ---
        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, -parallax);

        // --- Right Eye (Negative Effects Only) ---
        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, parallax);
    }

    private void drawEye(int program, float parallax) {
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
        GLES20.glUniform1f(convergenceHandle, 0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawWithShader() {
        if (prefConf != null) {
            drawBothEyes(dibr3dProgram, prefConf.parallax_depth);
        }
    }

    private int intermediateTextureId; // Für das Ergebnis des ersten Blur-Durchgangs
    private int intermediateFboHandle;

    private void initBuffer() {
        if (tflite != null) {
            int inputSize = modelInputHeight * modelInputWidth * 3;
            tfliteInputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());

            // NEU: Erstelle den Buffer-Pool
            int outputSize = modelInputHeight * modelInputWidth;
            freeOutputBuffers = new ArrayBlockingQueue<>(NUM_BUFFERS);
            filledOutputBuffers = new ArrayBlockingQueue<>(NUM_BUFFERS);
            for (int i = 0; i < NUM_BUFFERS; i++) {
                freeOutputBuffers.offer(ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder()));
            }
        }
    }

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

        // Return the buffer we used last frame to the free pool
        if (currentlyRenderingMap != null) {
            freeSmoothedBuffers.offer(currentlyRenderingMap);
            currentlyRenderingMap = null;
        }

        // Check for a new, completed frame. .poll() is non-blocking.
        ByteBuffer newMap = readyToRenderQueue.poll();
        if (newMap != null) {
            currentlyRenderingMap = newMap;
        }

        // Upload the latest completed map to the GPU.
        // If no new one was ready, it will re-upload the previous one.
        if (currentlyRenderingMap != null) {
            uploadLatestDepthMapToGpu(currentlyRenderingMap);
        }
        try {
            videoSurfaceTexture.updateTexImage();
        } catch (Exception e) {
            Log.w("Stereo3DRenderer", "updateTexImagse failed", e);
            return;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        applyTwoPassGaussianBlur();
        if (tflite != null) {
            if (!isAiResultHandlingRunning.get()) {
                isAiResultHandlingRunning.set(true);
                executorService.submit(new AiResultHandling());
            }
            if (!isAiRunning.get()) {
                isAiRunning.set(true);
                executorService.submit(new AiTask());
            }
            try {
                ByteBuffer pixelBufferForAI = freeInputBuffers.poll();
                if (pixelBufferForAI != null) {
                    // 1. Try to get a free, empty buffer from the resource pool.
                    // 2. A buffer was available. Fill it with the latest pixel data.
                    readPixelsForAI(pixelBufferForAI);

                    // 3. Offer the now-FILLED buffer to the AI's "to-do" queue.
                    if (inferenceInputQueue.offer(pixelBufferForAI)) {
                        // Success: The AI will now process this buffer.
                        Log.d("InferenceTask", "Success: The AI will now process this buffer.");
                    } else {
                        // The AI is still busy with the last job, and our single-slot
                        // "to-do" queue is full. This is rare but possible.
                        // Return the buffer we just filled immediately to the free pool.

                        freeInputBuffers.put(pixelBufferForAI);
                    }
                    // If pixelBufferForAI was null, it means all buffers are busy.
                    // We simply skip this frame, which is the correct back-pressure behavior.
                }
            } catch (InterruptedException e) {
            }
            drawWithShader();
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1000000;
            Log.d("Stereo3DRenderer", "Total onDrawFrame time: " + durationMs + " ms ");
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

    /**
     * Fills the provided ByteBuffer with pixel data from the FBO.
     * This method does NOT allocate a new buffer.
     *
     * @param destinationBuffer The pre-allocated buffer to write pixel data into.
     */
    private void readPixelsForAI(ByteBuffer destinationBuffer) {
        destinationBuffer.rewind();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        drawQuad(simple3dProgram, 1.0f, 0.0f);

        GLES20.glReadPixels(0, 0, modelInputWidth, modelInputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, destinationBuffer);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private ByteBuffer createFlatDepthMap() {
        int mapSize = modelInputWidth * modelInputHeight;
        byte[] flatData = new byte[mapSize];
        Arrays.fill(flatData, (byte) 128);

        // KORREKT: Verwende allocateDirect, um einen nativen Buffer zu erstellen.
        ByteBuffer flatMap = ByteBuffer.allocateDirect(mapSize).order(ByteOrder.nativeOrder());

        flatMap.put(flatData);
        flatMap.rewind();
        return flatMap;
    }

    private void uploadLatestDepthMapToGpu(ByteBuffer depthMap) {
        if (depthMap != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, modelInputWidth, modelInputHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, depthMap);
        }
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

    private class AiTask implements Runnable {

        @Override
        public void run() {
            ByteBuffer pixelBuffer = null;
            while (!Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                long waitTime = System.nanoTime();
                long aiTime = System.nanoTime();
                long aiTime_end = System.nanoTime();
                try {
                    if (tflite == null) return;
                    pixelBuffer = inferenceInputQueue.take();
                    Log.d("InferenceTask", "AI inference started");
                    // 1. Hole einen freien Buffer aus dem Pool (wartet, falls keiner verfügbar ist)
                    ByteBuffer outputBuffer = freeOutputBuffers.take();
                    waitTime = System.nanoTime();
                    outputBuffer.rewind();

                    // 2. Bereite den Input-Buffer vor (wie bisher)
                    tfliteInputBuffer.rewind();
                    pixelBuffer.rewind();

                    convertRgbaToRgb(pixelBuffer, tfliteInputBuffer, modelInputWidth, modelInputHeight);

                    aiTime = System.nanoTime();
                    tflite.run(tfliteInputBuffer, outputBuffer);
                    aiTime_end = System.nanoTime();
                    filledOutputBuffers.put(new InferenceResult(pixelBuffer, outputBuffer));
                    Log.d("InferenceTask", "AI inference ended");
                } catch (InterruptedException e) {
                    Log.d("InferenceTask", "AI inference failed", e);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.d("InferenceTask", "AI inference failed", e);
                    gpuDelegateFailed.set(true);
                } finally {
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    long waitTimeText = (waitTime - startTime) / 1_000_000;
                    long aitimeText = (aiTime_end - aiTime) / 1_000_000;
                    Log.d("Stereo3DRenderer", "CalculateTime AiDepthMap: " + duration + " ms " + filledOutputBuffers.remainingCapacity() + " " + waitTimeText + " ms" + "aitime: " + aitimeText);
                }
            }
            isAiRunning.set(false);
        }
    }

    private static class InferenceResult {
        final ByteBuffer pixelBuffer; // The original image
        final ByteBuffer rawDepthBuffer;  // The raw AI result

        InferenceResult(ByteBuffer pixelBuffer, ByteBuffer rawDepthBuffer) {
            this.pixelBuffer = pixelBuffer;
            this.rawDepthBuffer = rawDepthBuffer;
        }
    }

    /**
     * Converts an RGBA ByteBuffer to an RGB ByteBuffer using fast, native OpenCV functions.
     * This method is thread-safe and cleans up all native resources.
     *
     * @param rgbaBuffer The source buffer containing RGBA data.
     * @param rgbBuffer  The pre-allocated destination buffer for RGB data.
     * @param width      The width of the image.
     * @param height     The height of the image.
     */
    public static void convertRgbaToRgb(ByteBuffer rgbaBuffer, ByteBuffer rgbBuffer, int width, int height) {
        Mat rgbaMat = null;
        Mat rgbMat = null;
        try {
            // 1. Wrap the source and destination buffers in Mat headers (zero-copy)
            rgbaMat = new Mat(height, width, CvType.CV_8UC4, rgbaBuffer);
            rgbMat = new Mat(height, width, CvType.CV_8UC3, rgbBuffer);

            // 2. Perform the conversion using the highly optimized native function
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB);

        } finally {
            // 3. CRUCIAL: Always release the Mat headers to prevent memory leaks
            if (rgbaMat != null) {
                rgbaMat.release();
            }
            if (rgbMat != null) {
                rgbMat.release();
            }
        }
    }

    /**
     * Calculates the average pixel difference between two ByteBuffers using fast, native OpenCV functions.
     * This is a high-performance replacement for a manual Java for-loop.
     *
     * @param buffer1 The first ByteBuffer (e.g., newestBuffer).
     * @param buffer2 The second ByteBuffer (e.g., previousUnsmoothedDepthBytes).
     * @param width   The width of the image.
     * @param height  The height of the image.
     * @return The average difference per pixel (a value between 0 and 255).
     */
    public static double calculateAverageDifferenceOCV(ByteBuffer buffer1, ByteBuffer buffer2, int width, int height) {
        // Safety check for uninitialized buffers
        if (buffer1 == null || buffer2 == null) {
            return 255.0; // Return a high value to indicate a significant change
        }

        Mat mat1 = null;
        Mat mat2 = null;
        Mat diffMat = null;
        try {
            // 1. Wrap the buffers in Mat headers. This is a fast, zero-copy operation.
            mat1 = new Mat(height, width, CvType.CV_8UC1, buffer1);
            mat2 = new Mat(height, width, CvType.CV_8UC1, buffer2);

            // 2. Create a destination Mat to hold the difference image.
            diffMat = new Mat();

            // 3. Calculate the absolute difference for every pixel using a single, native call.
            Core.absdiff(mat1, mat2, diffMat);

            // 4. Calculate the mean (average) value of all pixels in the difference matrix.
            Scalar meanDifference = Core.mean(diffMat);

            // 5. The result for a single-channel image is in the first element of the Scalar.
            return meanDifference.val[0];

        } finally {
            // 6. CRUCIAL: Always release the Mat headers to prevent native memory leaks.
            if (mat1 != null) {
                mat1.release();
            }
            if (mat2 != null) {
                mat2.release();
            }
            if (diffMat != null) {
                diffMat.release();
            }
        }
    }

    private class AiResultHandling implements Runnable {

        private final byte[] processedDataArray = new byte[modelInputWidth * modelInputHeight];
        private ByteBuffer previousRawMap = null;

        @Override
        public void run() {
            ByteBuffer resultBuffer = createFlatDepthMap();
            InferenceResult result = null;
            while (!Thread.currentThread().isInterrupted()) {
                boolean takeResult = true;
                long startTime = System.nanoTime();
                long waitTime = System.nanoTime();
                Mat rawMat = null;
                Mat processedMat = null;
                double rawDifference = 0.0;
                try {
                    Log.d("Stereo3DRenderer", "checking AI started");
                    // 1. Warte auf ein gefülltes Ergebnis vom InferenceTask
                    result = filledOutputBuffers.take();
                    resultBuffer = freeSmoothedBuffers.take();

                    ByteBuffer rawDepthBuffer = result.rawDepthBuffer;
                    ByteBuffer currentPixelBuffer = result.pixelBuffer;

                    if (previousRawMap != null) {
                        // Vergleiche den rohen Buffer von diesem Frame mit dem vom letzten.
                        rawDifference = calculateAverageDifferenceOCV(rawDepthBuffer, previousRawMap, modelInputWidth, modelInputHeight);
                    }

                    // Aktualisiere 'previous' mit den aktuellen rohen Daten für den nächsten Test
                    if (previousRawMap == null || previousRawMap.capacity() != rawDepthBuffer.capacity()) {
                        previousRawMap = ByteBuffer.allocateDirect(rawDepthBuffer.capacity());
                    }
                    previousRawMap.rewind();
                    rawDepthBuffer.rewind();
                    previousRawMap.put(rawDepthBuffer);

                    InferenceResult intermediateBuffer;
                    while ((intermediateBuffer = filledOutputBuffers.poll()) != null) {
                        freeOutputBuffers.put(result.rawDepthBuffer);
                        rawDepthBuffer = intermediateBuffer.rawDepthBuffer;
                    }
                    rawDepthBuffer.rewind();
                    rawMat = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC1, rawDepthBuffer);
                    processedMat = new Mat();
                    Core.normalize(rawMat, processedMat, 0, 255, Core.NORM_MINMAX);
                    processedMat.get(0, 0, processedDataArray);
                    waitTime = System.nanoTime();
                    rawDepthBuffer.rewind();
                    rawDepthBuffer.put(processedDataArray);
                    double imageDifference = 0.0f;
                    final double INSTABILITY_THRESHOLD = 10.0;
                    final double INSTABILITY_THRESHOLD_STRONG = 1.0;
                    final double IMAGE_TRESHOLD = 6;
                    final double MIN_IMAGE_DIFFERENCE = 1f;

                    if (previousPixelBuffer != null) {
                        currentPixelBuffer.rewind();
                        imageDifference = hasFrameChangedSignificantlyOCV(currentPixelBuffer, previousPixelBuffer) * 100;
                        previousPixelBuffer.rewind();
                        previousPixelBuffer.put(currentPixelBuffer);
                        if (imageDifference < MIN_IMAGE_DIFFERENCE) {
                            takeResult = false;
                        }
                        imageDifference = Math.max(imageDifference, 0);
                        double instabilityFactor = rawDifference / Math.max(imageDifference, MIN_IMAGE_DIFFERENCE);


                        if (instabilityFactor > INSTABILITY_THRESHOLD_STRONG && imageDifference <= IMAGE_TRESHOLD) {
                            LimeLog.info("Unstable AI ignored strong " + instabilityFactor +
                                    " (DepthDiff: " + rawDifference + ", ImageDiff: " + imageDifference + ")");
                            takeResult = false;
                        } else if (instabilityFactor > INSTABILITY_THRESHOLD && imageDifference > IMAGE_TRESHOLD) {
                            LimeLog.info("Unstable AI ignored weak " + instabilityFactor +
                                    " (DepthDiff: " + rawDifference + ", ImageDiff: " + imageDifference + ")");
                            takeResult = false;
                        }
                        if (takeResult) {
                            LimeLog.info("New Result delivered for processing " + instabilityFactor +
                                    " (DepthDiff: " + rawDifference + ", ImageDiff: " + imageDifference + ")");
                        }
                    }

                    if (takeResult) {
                        rawDepthBuffer.rewind();
                        resultBuffer.rewind();

                        resultBuffer.put(rawDepthBuffer);

                        rawDepthBuffer.rewind();
                        resultBuffer.rewind();
                        readyToRenderQueue.put(resultBuffer);
                    }
                    freeOutputBuffers.put(rawDepthBuffer);
                } catch (Exception e) {
                    Log.e("Stereo3DRenderer", "AIRESULT exception", e);
                } finally {
                    if (rawMat != null) {
                        rawMat.release();
                    }
                    if (processedMat != null) {
                        processedMat.release();
                    }
                    if (resultBuffer != null) {
                        freeSmoothedBuffers.offer(resultBuffer);
                    }
                    if (result != null) {
                        freeInputBuffers.offer(result.pixelBuffer);
                    }
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    long waitTimeText = (waitTime - startTime) / 1_000_000;
                    Log.d("Stereo3DRenderer", "CalculateTime AiResult:    " + duration + " ms" + " " + freeOutputBuffers.remainingCapacity() + " " + waitTimeText + " ms");
                }
            }
            Log.d("Stereo3DRenderer", "Total AI RESULT FALSE");
            isAiResultHandlingRunning.set(false);
        }
    }

    /**
     * Compares two frames using OpenCV's histogram correlation to robustly detect scene changes.
     *
     * @param newPixelBuffer The RGBA buffer of the current frame.
     * @param oldPixelBuffer The RGBA buffer of the previous frame.
     * @return A "difference score" from 0.0 (identical) to 1.0 (very different).
     */
    private double hasFrameChangedSignificantlyOCV(ByteBuffer newPixelBuffer, ByteBuffer oldPixelBuffer) {
        if (newPixelBuffer == null || oldPixelBuffer == null || newPixelBuffer.capacity() != oldPixelBuffer.capacity()) {
            return 1.0; // Assume maximum change if buffers are invalid
        }

        Mat mat1 = null;
        Mat mat2 = null;
        Mat grayMat1 = null;
        Mat grayMat2 = null;
        Mat hist1 = null;
        Mat hist2 = null;

        try {
            // 1. Wrap the ByteBuffers in Mat headers (no data is copied)
            mat1 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, newPixelBuffer);
            mat2 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, oldPixelBuffer);

            // 2. Convert the images to grayscale for a simpler and more stable comparison
            grayMat1 = new Mat();
            grayMat2 = new Mat();
            Imgproc.cvtColor(mat1, grayMat1, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(mat2, grayMat2, Imgproc.COLOR_RGBA2GRAY);

            // 3. Calculate the histogram for each grayscale image
            hist1 = new Mat();
            hist2 = new Mat();
            Imgproc.calcHist(Collections.singletonList(grayMat1), new MatOfInt(0), new Mat(), hist1, new MatOfInt(256), new MatOfFloat(0f, 256f));
            Imgproc.calcHist(Collections.singletonList(grayMat2), new MatOfInt(0), new Mat(), hist2, new MatOfInt(256), new MatOfFloat(0f, 256f));

            // 4. Compare the two histograms using the correlation method
            //    Result is between -1.0 (inverse) and 1.0 (identical).
            double correlation = Imgproc.compareHist(hist1, hist2, Imgproc.HISTCMP_CORREL);

            // 5. Convert the correlation score into a "difference" score
            //    1.0 becomes 0.0 (no difference)
            //    0.0 becomes 1.0 (high difference)
            return 1.0 - correlation;

        } finally {
            // CRUCIAL: ALWAYS release Mat memory to prevent leaks
            if (mat1 != null) mat1.release();
            if (mat2 != null) mat2.release();
            if (grayMat1 != null) grayMat1.release();
            if (grayMat2 != null) grayMat2.release();
            if (hist1 != null) hist1.release();
            if (hist2 != null) hist2.release();
        }
    }

    private final int NUM_BUFFERS = 6; // Poolgröße, 2-3 ist meist ideal
    private BlockingQueue<ByteBuffer> freeOutputBuffers;
    private BlockingQueue<InferenceResult> filledOutputBuffers;
    private BlockingQueue<ByteBuffer> inferenceInputQueue = new ArrayBlockingQueue<>(1);

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
