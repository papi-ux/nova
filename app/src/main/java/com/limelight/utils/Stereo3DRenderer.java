package com.limelight.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
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


    /**
     * A simple data class to hold the result from the AI background task.
     */
    private static class DepthResult {
        final ByteBuffer depthMap;
        final float averageDepth;

        DepthResult(ByteBuffer depthMap, float averageDepth) {
            this.depthMap = depthMap;
            this.averageDepth = averageDepth;
        }
    }

    private final AtomicReference<DepthResult> latestDepthResult = new AtomicReference<>();

    private float smoothedAverageSceneDepth = 0.0f;
    private float targetAverageSceneDepth = 0.0f;
    private Surface videoSurface;
    private int videoTextureId;
    private int depthMapTextureId;
    private int fboHandle;
    private int fboTextureId;

    private int simple3dProgram;

    private int fake3dProgram;

    private int dibr3dProgram;

    private Interpreter tflite;
    private GpuDelegate gpuDelegate;
    private int modelInputWidth = 256;
    private int modelInputHeight = 256;

    private ByteBuffer previousDepthBytes = null;
    private ExecutorService executorService;

    // Variables for dynamic convergence
    private volatile float averageSceneDepth = 0.5f;
    private final Object frameLock = new Object();
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
        fake3dProgram = createProgram(ShaderUtils.SIMPLE_VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_FAKE_3D);
        dibr3dProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_DIBR_DYN_CONVERGENCE_3D);

        initializeTfLite();
        initializeFbo();
        initBuffer();

        executorService = Executors.newSingleThreadExecutor();

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onSurfaceReady(videoSurface);
        }
    }


    private void drawWithDibr(int program, float parallax, float convergenceStrength) {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        // Left eye (unmodified)
        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);

        // Right eye (synthesized)
        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        GLES20.glUseProgram(program);

        int posHandle = GLES20.glGetAttribLocation(program, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        float dynamicConvergence = (smoothedAverageSceneDepth) * (convergenceStrength / 10f);

        int colorTexHandle = GLES20.glGetUniformLocation(program, "s_ColorTexture");
        int depthTexHandle = GLES20.glGetUniformLocation(program, "s_DepthTexture");
        int parallaxHandle = GLES20.glGetUniformLocation(program, "u_parallax");
        int convergenceHandle = GLES20.glGetUniformLocation(program, "u_convergence");
        if (convergenceHandle != -1) {
            GLES20.glUniform1f(convergenceHandle, dynamicConvergence);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glUniform1i(colorTexHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
        GLES20.glUniform1i(depthTexHandle, 1);

        GLES20.glUniform1f(parallaxHandle, parallax);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }


    private void drawWithDibrAiSubtleShader() {
        drawWithDibr(dibr3dProgram, 0.02f, 0.04f);
    }

    private void drawWithDibrAiStrongShader() {
        drawWithDibr(dibr3dProgram, 0.04f, 0.08f);
    }

    private void initBuffer() {
        if (tflite != null) {
            // Annahme: Input ist ein Float-Tensor [1, H, W, 3]
            int inputSize = 1 * modelInputHeight * modelInputWidth * 3 * 4; // 1*H*W*C*BytesPerFloat
            tfliteInputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());

            // Annahme: Output ist ein Float-Tensor [1, H, W, 1]
            int outputSize = 1 * modelInputHeight * modelInputWidth * 1 * 4; // 1*H*W*C*BytesPerFloat
            tfliteOutputBuffer = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder());
        }
    }

    private ByteBuffer previousSmoothedDepthBytes = null;
    private ByteBuffer latestUnsmoothedDepthMap = null;
    private int frameCount = 0;

    private final Object depthMapLock = new Object();

    private void smoothConvergence() {
        smoothedAverageSceneDepth = (targetAverageSceneDepth * 0.1f) + (smoothedAverageSceneDepth * 0.9f);
    }

    private void smoothAndUpdateDepthMap() {
        synchronized (depthMapLock) {
            // If there's a new unsmoothed map from the AI, smooth it.
            if (latestUnsmoothedDepthMap != null) {
                if (previousSmoothedDepthBytes == null || previousSmoothedDepthBytes.capacity() != latestUnsmoothedDepthMap.capacity()) {
                    previousSmoothedDepthBytes = ByteBuffer.allocate(latestUnsmoothedDepthMap.capacity());
                }

                // Your adaptive smoothing logic here
                float currentSmoothingFactor = 0.9f; // Replace with your adaptive logic if needed

                latestUnsmoothedDepthMap.rewind();
                previousSmoothedDepthBytes.rewind();
                ByteBuffer newSmoothedBytes = ByteBuffer.allocate(latestUnsmoothedDepthMap.capacity()).order(ByteOrder.nativeOrder());

                for (int i = 0; i < latestUnsmoothedDepthMap.capacity(); i++) {
                    byte prev = previousSmoothedDepthBytes.get(i);
                    byte curr = latestUnsmoothedDepthMap.get(i);
                    byte smooth = (byte) ((1.0f - currentSmoothingFactor) * (curr & 0xFF) + currentSmoothingFactor * (prev & 0xFF));
                    newSmoothedBytes.put(i, smooth);
                }

                // Upload the newly smoothed map to the GPU
                uploadLatestDepthMapToGpu(newSmoothedBytes);

                // Save this smoothed map as the new "previous" map
                previousSmoothedDepthBytes = newSmoothedBytes;
                latestUnsmoothedDepthMap = null;
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

        try {
            videoSurfaceTexture.updateTexImage();
        } catch (Exception e) {
            Log.w("Stereo3DRenderer", "updateTexImage failed", e);
            return;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        DepthResult newResult = latestDepthResult.getAndSet(null);
        if (newResult != null) {
            synchronized (depthMapLock) {
                latestUnsmoothedDepthMap = newResult.depthMap;
            }
            targetAverageSceneDepth = newResult.averageDepth;
        }

        // Smooth the convergence value and the depth map in every frame
        smoothConvergence();
        smoothAndUpdateDepthMap();
        boolean pixelWereCount = false;
        if (tflite != null) {
            if (!isAiRunning.get()) {
                if (frameCount == 0) {
                    ByteBuffer pixelBuffer = readPixelsForAI();
                    pixelWereCount = true;
                    if (hasFrameChangedSignificantly(pixelBuffer) || frameCount % 120 == 0) {
                        isAiRunning.set(true);
                        executorService.submit(new DepthEstimationTask(pixelBuffer));
                        if (frameCount == 120) frameCount = 0;
                    }
                }
            }

            drawWithShader();
            frameCount++;
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1000000;
            Log.d("Stereo3DRenderer", "Total onDrawFrame time: " + durationMs + " ms " + pixelWereCount);
        }

    }

    private ByteBuffer previousPixelBufferForDiff = null;

    /**
     * Compares the new pixel buffer to the last analyzed one to decide if a new AI inference is needed.
     *
     * @param newPixelBuffer The pixel data of the current frame.
     * @return True if the frame has changed significantly, false otherwise.
     */
    private boolean hasFrameChangedSignificantly(ByteBuffer newPixelBuffer) {
        if (previousPixelBufferForDiff == null) {
            previousPixelBufferForDiff = newPixelBuffer;
            return true; // Always process the first frame
        }

        if (newPixelBuffer.capacity() != previousPixelBufferForDiff.capacity()) {
            previousPixelBufferForDiff = newPixelBuffer;
            return true; // Process if buffer size changes
        }

        long totalDifference = 0;
        newPixelBuffer.rewind();
        previousPixelBufferForDiff.rewind();

        for (int i = 0; i < newPixelBuffer.capacity(); i++) {
            totalDifference += Math.abs((newPixelBuffer.get(i) & 0xFF) - (previousPixelBufferForDiff.get(i) & 0xFF));
        }

        double averageDifference = (double) totalDifference / newPixelBuffer.capacity();
        LimeLog.info("Difference on pixel level " + averageDifference);
        double CHANGE_THRESHOLD = 5;
        if (averageDifference > CHANGE_THRESHOLD) {
            previousPixelBufferForDiff = newPixelBuffer;
            return true;
        }

        return false;
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
                drawWithDibrAiSubtleShader();
                break;
            case MODE_FASTPATH_3D:
                drawWithFake3DShader();
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

    private void drawWithFake3DShader() {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);

        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        drawQuad(fake3dProgram, 1.0f, stereoOffset);
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

                // Die Member-Variablen-Buffer zurücksetzen, anstatt neue zu erstellen.
                tfliteInputBuffer.rewind();
                tfliteOutputBuffer.rewind();

                // 1. Konvertiere die Pixel in das Float-Format des Modells
                pixelBuffer.rewind();
                for (int i = 0; i < modelInputWidth * modelInputHeight; i++) {
                    tfliteInputBuffer.putFloat((pixelBuffer.get() & 0xFF) / 255.0f); // R
                    tfliteInputBuffer.putFloat((pixelBuffer.get() & 0xFF) / 255.0f); // G
                    tfliteInputBuffer.putFloat((pixelBuffer.get() & 0xFF) / 255.0f); // B
                    pixelBuffer.get(); // Alpha überspringen
                }
                tfliteInputBuffer.rewind();

                // 2. Führe die KI-Inferenz aus
                tflite.run(tfliteInputBuffer, tfliteOutputBuffer);
                tfliteOutputBuffer.rewind();

                // 3. Normalisiere das Ergebnis
                FloatBuffer floatOutput = tfliteOutputBuffer.asFloatBuffer();
                float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
                for (int i = 0; i < floatOutput.capacity(); i++) {
                    float val = floatOutput.get(i);
                    if (val < min) min = val;
                    if (val > max) max = val;
                }
                float range = max - min;
                ByteBuffer depthBytes = ByteBuffer.allocateDirect(modelInputWidth * modelInputHeight).order(ByteOrder.nativeOrder());
                if (range > 0) {
                    floatOutput.rewind();
                    for (int i = 0; i < floatOutput.capacity(); i++) {
                        float normalized = (floatOutput.get() - min) / range;
                        depthBytes.put((byte) (normalized * 255.0f));
                    }
                }
                depthBytes.rewind();

               /* final double CHANGE_THRESHOLD = 80.0; // Durchschnittliche Pixeländerung für einen Szenenwechsel
                final float HIGH_SMOOTHING = 0.9f;   // Starke Glättung für stabile Szenen
                final float LOW_SMOOTHING = 0.01f;  // Leichte Glättung für schnelle Szenenwechsel

                float currentSmoothingFactor = HIGH_SMOOTHING; // Standard

                if (previousDepthBytes != null && previousDepthBytes.capacity() == depthBytes.capacity()) {
                    long totalDifference = 0;
                    // Berechne den Unterschied zwischen der NEUEN rohen und der VORHERIGEN geglätteten Tiefenkarte
                    for (int i = 0; i < depthBytes.capacity(); i++) {
                        totalDifference += Math.abs((depthBytes.get(i) & 0xFF) - (previousDepthBytes.get(i) & 0xFF));
                    }
                    double averageDifference = (double) totalDifference / depthBytes.capacity();

                    if (averageDifference > CHANGE_THRESHOLD) {
                        currentSmoothingFactor = LOW_SMOOTHING;
                    } else if (averageDifference > 20) {
                        currentSmoothingFactor = HIGH_SMOOTHING;
                    } else {
                        currentSmoothingFactor = 1f;
                    }
                    depthBytes.rewind();
                    for (int i = 0; i < depthBytes.capacity(); i++) {
                        byte prev = previousDepthBytes.get(i);
                        byte curr = depthBytes.get(i);
                        byte smooth = (byte) ((1.0f - currentSmoothingFactor) * (curr & 0xFF) + currentSmoothingFactor * (prev & 0xFF));
                        depthBytes.put(i, smooth);
                    }
                }

                depthBytes.rewind();

                previousDepthBytes = ByteBuffer.allocate(depthBytes.capacity()).put(depthBytes);
                previousDepthBytes.rewind();
                depthBytes.rewind();
*/
                long depthSum = 0;
                for (int i = 0; i < depthBytes.capacity(); i++) {
                    depthSum += (depthBytes.get(i) & 0xFF);
                }
                float newAverageDepth = (float) (depthSum / (double) depthBytes.capacity()) / 255.0f;

                // Create a result object and pass it to the main thread
                latestDepthResult.set(new DepthResult(depthBytes, newAverageDepth));

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
        try {
            CompatibilityList compat = new CompatibilityList();
            Interpreter.Options opts = new Interpreter.Options();

            GpuDelegate delegate = new GpuDelegate(compat.getBestOptionsForThisDevice());
            opts.addDelegate(delegate);
            tflite = new Interpreter(loadModelFile(context, "Midas-V2.tflite"), opts);
        } catch (Exception e) {
            Log.w("Stereo3DRenderer", "Failed to create TFLite interpreter with GPU delegate, falling back to CPU.", e);
            reinitializeTfLiteOnCpu();
            // Beenden, da reinitializeTfLiteOnCpu den Rest erledigt
            return;
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
            tflite = new Interpreter(loadModelFile(context, "Midas-V2.tflite"), options);
            Log.i("Stereo3DRenderer", "Successfully re-initialized TFLite interpreter on CPU.");
        } catch (IOException e) {
            Log.e("Stereo3DRenderer", "Failed to re-initialize TFLite model on CPU.", e);
        }
    }

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
