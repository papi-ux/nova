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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Stereo3DRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    // Constants
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private static final float[] TEXTURE_VERTICES = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    private final String AI_MODEL = "midas-midas-v2-w8a8.tflite";
    private final int modelInputHeight = 256;
    private final int modelInputWidth = 256;
    private final int NUM_BUFFERS = 6;
    private final int NUM_INPUT_BUFFERS = 10;
    private final int NUM_SMOOTHED_BUFFERS = 3;
    private final int[] pboHandles = new int[2];
    private int pboIndex = 0;
    private int PBO_SIZE = modelInputWidth * modelInputHeight * 4;

    // Public Static Fields
    public static volatile float fps = 0;
    public static volatile float threeDFps = 0;
    public static volatile float drawDelay = 0.0f;
    public static Boolean isDebugMode = false;
    public static Boolean isActive = false;
    public static String renderer = "CPU";

    // Private Static Fields
    private static float calcFps = 0;
    private static int depthMapResultCount = 0;
    private static float calcThreeDFps = 0;

    // Final Member Variables
    private final Context context;
    private final GLSurfaceView glSurfaceView;
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    private final Object frameLock = new Object();
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private final AtomicBoolean gpuDelegateFailed = new AtomicBoolean(false);
    private final AtomicBoolean isAiResultHandlingRunning = new AtomicBoolean(false);
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);

    // OpenGL Handles
    private int bilateralBlurProgram;
    private int depthMapTextureId;
    private int dibr3dProgram;
    private int fboHandle;
    private int fboTextureId;
    private int filterFboHandle;
    private int filteredDepthMapTextureId;
    private int intermediateFboHandle;
    private int intermediateTextureId;
    private int simple3dProgram;
    private int videoTextureId;

    // AI & TFLite Variables
    private GpuDelegate gpuDelegate;
    private Interpreter tflite;
    private NnApiDelegate nnApiDelegate;
    private ByteBuffer tfliteInputBuffer;

    // Other Member Variables
    private long totalDrawTime = 0;
    private long lastFpsTime = 0;
    private ByteBuffer previousFrameForComparison;
    private ByteBuffer currentlyRenderingMap;
    private ExecutorService executorService;
    private BlockingQueue<InferenceResult> filledOutputBuffers;
    private BlockingQueue<ByteBuffer> freeInputBuffers;
    private BlockingQueue<ByteBuffer> freeOutputBuffers;
    private BlockingQueue<ByteBuffer> freeSmoothedBuffers;
    private BlockingQueue<ByteBuffer> inferenceInputQueue = new ArrayBlockingQueue<>(1);
    private PreferenceConfiguration prefConf;
    private ByteBuffer previousPixelBuffer;
    private BlockingQueue<ByteBuffer> readyToRenderQueue;
    private Surface videoSurface;
    private SurfaceTexture videoSurfaceTexture;


    public interface OnSurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }

    static {
        if (!OpenCVLoader.initLocal()) {
            LimeLog.severe("Internal OpenCV library not found. Using OpenCV Manager for initialization");
        } else {
            LimeLog.info("OpenCV library found inside package. Using it!");
        }
    }

    public Stereo3DRenderer(GLSurfaceView view, OnSurfaceReadyListener listener, Context context) {
        this.glSurfaceView = view;
        this.onSurfaceReadyListener = listener;
        this.context = context;

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).position(0);
        textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureVertexBuffer.put(TEXTURE_VERTICES).position(0);
    }

    public void onSurfaceDestroyed() {
        LimeLog.info("Quit called. Shutting down 3dRenderer.");
        if (executorService != null) {
            executorService.shutdownNow();
            try {
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    LimeLog.warning("Thread pool did not terminate in time.");
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (nnApiDelegate != null) {
            nnApiDelegate.close();
            nnApiDelegate = null;
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }

        glSurfaceView.queueEvent(() -> {
            GLES20.glDeleteProgram(simple3dProgram);
            GLES20.glDeleteProgram(bilateralBlurProgram);
            GLES20.glDeleteProgram(dibr3dProgram);

            int[] textures = {
                    videoTextureId,
                    depthMapTextureId,
                    filteredDepthMapTextureId,
                    fboTextureId,
                    intermediateTextureId
            };
            GLES20.glDeleteTextures(textures.length, textures, 0);

            int[] fbos = {fboHandle, intermediateFboHandle, filterFboHandle};
            GLES20.glDeleteFramebuffers(fbos.length, fbos, 0);
        });

        if (readyToRenderQueue != null) readyToRenderQueue.clear();
        if (filledOutputBuffers != null) filledOutputBuffers.clear();
        previousPixelBuffer = null;
        currentlyRenderingMap = null;
        prefConf = null;
        drawDelay = 0.0f;
        calcFps = 0;
        calcThreeDFps = 0.0f;
        renderer = "CPU";
        isActive = false;
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
        bilateralBlurProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER);
        dibr3dProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_3D);

        initializeFilterFbo();
        initializeIntermediateFbo();
        initializeTfLite();
        initializeFbo();
        initBuffer();
        initializePBOs();

        int mapSize = modelInputWidth * modelInputHeight;
        freeSmoothedBuffers = new ArrayBlockingQueue<>(NUM_SMOOTHED_BUFFERS);
        readyToRenderQueue = new ArrayBlockingQueue<>(NUM_SMOOTHED_BUFFERS);
        for (int i = 0; i < NUM_SMOOTHED_BUFFERS; i++) {
            freeSmoothedBuffers.offer(ByteBuffer.allocateDirect(mapSize).order(ByteOrder.nativeOrder()));
        }

        int pboSize = modelInputWidth * modelInputHeight * 4;
        previousPixelBuffer = ByteBuffer.allocateDirect(pboSize).order(ByteOrder.nativeOrder());
        previousFrameForComparison = ByteBuffer.allocateDirect(pboSize).order(ByteOrder.nativeOrder());
        int inputPixelSize = modelInputWidth * modelInputHeight * 4;
        freeInputBuffers = new ArrayBlockingQueue<>(NUM_INPUT_BUFFERS);
        inferenceInputQueue = new ArrayBlockingQueue<>(1);
        for (int i = 0; i < NUM_INPUT_BUFFERS; i++) {
            freeInputBuffers.offer(ByteBuffer.allocateDirect(inputPixelSize).order(ByteOrder.nativeOrder()));
        }

        executorService = Executors.newFixedThreadPool(2);

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onSurfaceReady(videoSurface);
        }
        if (!isAiResultHandlingRunning.get()) {
            isAiResultHandlingRunning.set(true);
            executorService.submit(new AiResultHandling());
        }
        if (!isAiRunning.get()) {
            isAiRunning.set(true);
            executorService.submit(new AiTask());
        }
        isActive = true;
    }

    private void initializeIntermediateFbo() {
        intermediateTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        intermediateFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, intermediateTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("Intermediate Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void applyTwoPassGaussianBlur() {
        int blurProgram = bilateralBlurProgram;

        GLES20.glUseProgram(blurProgram);

        int posHandle = GLES20.glGetAttribLocation(blurProgram, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(blurProgram, "a_TexCoord");
        int inputTextureHandle = GLES20.glGetUniformLocation(blurProgram, "s_InputTexture");
        int texelSizeHandle = GLES20.glGetUniformLocation(blurProgram, "u_texelSize");
        int directionHandle = GLES20.glGetUniformLocation(blurProgram, "u_blurDirection");

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        GLES20.glUniform2f(texelSizeHandle, 1.0f / modelInputWidth, 1.0f / modelInputHeight);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        GLES20.glUniform2f(directionHandle, 1.0f, 0.0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
        GLES20.glUniform1i(inputTextureHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        GLES20.glUniform2f(directionHandle, 0.0f, 1.0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTextureId);
        GLES20.glUniform1i(inputTextureHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void drawBothEyes(int dualBubble3dProgram, float parallaxStrength) {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        float parallax = parallaxStrength * 0.03f;

        LimeLog.info("PArallax Strength " + parallax + " " + parallaxStrength);

        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, -parallax);

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
        int debugModeHandle = GLES20.glGetUniformLocation(program, "u_debugMode");

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        GLES20.glUniform1i(debugModeHandle, isDebugMode ? 1 : 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glUniform1i(colorTexHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId);
        GLES20.glUniform1i(depthTexHandle, 1);

        GLES20.glUniform1f(parallaxHandle, parallax);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawWithShader() {
        if (prefConf != null) {
            drawBothEyes(dibr3dProgram, prefConf.parallax_depth);
        }
    }

    private void initBuffer() {
        if (tflite != null) {
            int inputSize = modelInputHeight * modelInputWidth * 3;
            tfliteInputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());
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

        if (currentlyRenderingMap != null) {
            freeSmoothedBuffers.offer(currentlyRenderingMap);
            currentlyRenderingMap = null;
        }

        ByteBuffer newMap = readyToRenderQueue.poll();
        if (newMap != null) {
            currentlyRenderingMap = newMap;
            depthMapResultCount++;
        }

        if (currentlyRenderingMap != null) {
            uploadLatestDepthMapToGpu(currentlyRenderingMap);
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        applyTwoPassGaussianBlur();
        drawWithShader();
        if (tflite != null) {
            ByteBuffer pixelBufferForAI = freeInputBuffers.poll();
            if (pixelBufferForAI != null) {
                boolean success = readPixelsForAI_Async(pixelBufferForAI);

                if (success) {
                    if (hasSceneChangedFast(pixelBufferForAI, previousFrameForComparison)) {
                        pixelBufferForAI.rewind();
                        previousFrameForComparison.rewind();
                        previousFrameForComparison.put(pixelBufferForAI);

                        if (inferenceInputQueue.offer(pixelBufferForAI)) {
                            Log.d("AiTask", "Success: The AI will now process this buffer.");
                        } else {
                            freeInputBuffers.offer(pixelBufferForAI);
                        }
                    } else {
                        Log.d("AiTask", "Fail: The AI will now clear this buffer.");
                        freeInputBuffers.offer(pixelBufferForAI);
                    }
                } else {
                    freeInputBuffers.offer(pixelBufferForAI);
                }
            }
            long endTime = System.nanoTime();

            if (lastFpsTime == 0) {
                lastFpsTime = startTime;
            }
            totalDrawTime = totalDrawTime + (endTime - lastFpsTime);

            if (endTime - lastFpsTime >= 1_000_000_000) {
                if (fps > 0) {
                    drawDelay = ((float) totalDrawTime / fps / 1000000000f);
                }
                totalDrawTime = 0;

                fps = calcFps;
                calcFps = 0;
                depthMapResultCount = 0;
                threeDFps = calcThreeDFps;
                calcThreeDFps = 0;
                lastFpsTime = endTime;
                int freeInputCap = freeInputBuffers.size() + freeInputBuffers.remainingCapacity();
                int aiInCap = inferenceInputQueue.size() + inferenceInputQueue.remainingCapacity();
                int aiOutCap = filledOutputBuffers.size() + filledOutputBuffers.remainingCapacity();
                int readyRenderCap = readyToRenderQueue.size() + readyToRenderQueue.remainingCapacity();
                int freeSmoothCap = freeSmoothedBuffers.size() + freeSmoothedBuffers.remainingCapacity();

                String queueStatus = String.format(
                        "Queues (Free/Cap) | FreeInput: %d/%d, To_AI: %d/%d, From_AI: %d/%d, To_Render: %d/%d, Free_Smooth: %d/%d",
                        freeInputBuffers.remainingCapacity(), freeInputCap,
                        inferenceInputQueue.remainingCapacity(), aiInCap,
                        filledOutputBuffers.remainingCapacity(), aiOutCap,
                        readyToRenderQueue.remainingCapacity(), readyRenderCap,
                        freeSmoothedBuffers.remainingCapacity(), freeSmoothCap
                );
                Log.d("Stereo3DRenderer", queueStatus);
            } else {
                calcFps++;
            }
        }
    }

    private ByteBuffer createFlatDepthMap() {
        int mapSize = modelInputWidth * modelInputHeight;
        byte[] flatData = new byte[mapSize];
        Arrays.fill(flatData, (byte) 128);

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

    private static class InferenceResult {
        final ByteBuffer pixelBuffer;
        final ByteBuffer rawDepthBuffer;

        InferenceResult(ByteBuffer pixelBuffer, ByteBuffer rawDepthBuffer) {
            this.pixelBuffer = pixelBuffer;
            this.rawDepthBuffer = rawDepthBuffer;
        }
    }

    public static void convertRgbaToRgb(ByteBuffer rgbaBuffer, ByteBuffer rgbBuffer, int width, int height) {
        Mat rgbaMat = null;
        Mat rgbMat = null;
        try {
            rgbaMat = new Mat(height, width, CvType.CV_8UC4, rgbaBuffer);
            rgbMat = new Mat(height, width, CvType.CV_8UC3, rgbBuffer);
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB);
        } finally {
            if (rgbaMat != null) {
                rgbaMat.release();
            }
            if (rgbMat != null) {
                rgbMat.release();
            }
        }
    }

    public static double calculateAverageDifferenceOCV(ByteBuffer buffer1, ByteBuffer buffer2, int width, int height) {
        if (buffer1 == null || buffer2 == null) {
            return 255.0;
        }

        Mat mat1 = null;
        Mat mat2 = null;
        Mat diffMat = null;
        try {
            mat1 = new Mat(height, width, CvType.CV_8UC1, buffer1);
            mat2 = new Mat(height, width, CvType.CV_8UC1, buffer2);
            diffMat = new Mat();
            Core.absdiff(mat1, mat2, diffMat);
            Scalar meanDifference = Core.mean(diffMat);
            return meanDifference.val[0];
        } finally {
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

    private void initializePBOs() {
        PBO_SIZE = modelInputWidth * modelInputHeight * 4;

        GLES30.glGenBuffers(2, pboHandles, 0);

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[0]);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ);

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[1]);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ);

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
    }

    private boolean readPixelsForAI_Async(ByteBuffer destinationBuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);
        int writeIndex = pboIndex;
        int readIndex = (pboIndex + 1) % 2;
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[writeIndex]);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            GLES30.glReadPixels(0, 0, modelInputWidth, modelInputHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0);
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[readIndex]);
        ByteBuffer mappedBuffer = (ByteBuffer) GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER, 0, PBO_SIZE, GLES30.GL_MAP_READ_BIT);
        boolean success = false;
        if (mappedBuffer != null) {
            destinationBuffer.rewind();
            mappedBuffer.rewind();
            destinationBuffer.put(mappedBuffer);
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
            success = true;
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        pboIndex = readIndex;
        return success;
    }

    private void initializeTfLite() {
        Interpreter.Options options = new Interpreter.Options();

        try {
            GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
            gpuOptions.setQuantizedModelsAllowed(true);
            gpuOptions.setPrecisionLossAllowed(true);
            gpuOptions.setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
            gpuDelegate = new GpuDelegate(gpuOptions);
            options.addDelegate(gpuDelegate);
            LimeLog.info("GPU Delegate aktiviert");
            renderer = "GPU";
            tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
        } catch (Exception e) {
            LimeLog.info("GPU Delegate nicht verfügbar: " + e.getMessage());
            gpuDelegate.close();
            try {
                nnApiDelegate = new NnApiDelegate();
                options.addDelegate(nnApiDelegate);
                tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
                LimeLog.info("NNAPI Delegate aktiviert");
                renderer = "NNAPI";
            } catch (Exception exception) {
                LimeLog.info("NNAPI Delegate nicht verfügbar: " + e.getMessage());
                nnApiDelegate.close();
                try {
                    LimeLog.info("Fallback: CPU");
                    tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
                    renderer = "CPU";
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
            LimeLog.info("Successfully re-initialized TFLite interpreter on CPU.");
        } catch (IOException e) {
            LimeLog.severe("Failed to re-initialize TFLite model on CPU: "  + e.getMessage());
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
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
            LimeLog.severe("Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void initializeFilterFbo() {
        filteredDepthMapTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        filterFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.severe("Filter Framebuffer is not complete.");
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
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
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
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
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
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("Could not compile shader " + type + ":");
            LimeLog.severe(GLES20.glGetShaderInfoLog(shader));
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
                LimeLog.severe("Could not link program: ");
                LimeLog.severe(GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    private double hasFrameChangedSignificantlyOCV(ByteBuffer newPixelBuffer, ByteBuffer oldPixelBuffer) {
        if (newPixelBuffer == null || oldPixelBuffer == null || newPixelBuffer.capacity() != oldPixelBuffer.capacity()) {
            return 1.0;
        }

        Mat mat1 = null;
        Mat mat2 = null;
        Mat grayMat1 = null;
        Mat grayMat2 = null;
        Mat hist1 = null;
        Mat hist2 = null;

        try {
            mat1 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, newPixelBuffer);
            mat2 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, oldPixelBuffer);

            grayMat1 = new Mat();
            grayMat2 = new Mat();
            Imgproc.cvtColor(mat1, grayMat1, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(mat2, grayMat2, Imgproc.COLOR_RGBA2GRAY);

            hist1 = new Mat();
            hist2 = new Mat();
            Imgproc.calcHist(Collections.singletonList(grayMat1), new MatOfInt(0), new Mat(), hist1, new MatOfInt(256), new MatOfFloat(0f, 256f));
            Imgproc.calcHist(Collections.singletonList(grayMat2), new MatOfInt(0), new Mat(), hist2, new MatOfInt(256), new MatOfFloat(0f, 256f));

            double correlation = Imgproc.compareHist(hist1, hist2, Imgproc.HISTCMP_CORREL);

            return 1.0 - correlation;

        } finally {
            if (mat1 != null) mat1.release();
            if (mat2 != null) mat2.release();
            if (grayMat1 != null) grayMat1.release();
            if (grayMat2 != null) grayMat2.release();
            if (hist1 != null) hist1.release();
            if (hist2 != null) hist2.release();
        }
    }

    private boolean hasSceneChangedFast(ByteBuffer currentFrame, ByteBuffer previousFrame) {
        if (currentFrame == null || previousFrame == null || currentFrame.capacity() != previousFrame.capacity()) {
            return true;
        }

        currentFrame.rewind();
        previousFrame.rewind();

        long totalDifference = 0;
        int pixelsSampled = 0;

        final int PIXEL_STRIDE = 4;
        final int PIXEL_SAMPLE_RATE = 32;
        final int ROW_SAMPLE_RATE = 32;
        final int SAMPLE_STRIDE = PIXEL_STRIDE * PIXEL_SAMPLE_RATE;
        final int ROW_STRIDE = modelInputWidth * PIXEL_STRIDE * ROW_SAMPLE_RATE;

        for (int row = 0; row < currentFrame.capacity(); row += ROW_STRIDE) {
            for (int col = 0; col < modelInputWidth * PIXEL_STRIDE; col += SAMPLE_STRIDE) {
                int index = row + col;
                if (index + 2 >= currentFrame.capacity()) break;

                totalDifference += Math.abs((currentFrame.get(index) & 0xFF) - (previousFrame.get(index) & 0xFF));
                totalDifference += Math.abs((currentFrame.get(index + 1) & 0xFF) - (previousFrame.get(index + 1) & 0xFF));
                totalDifference += Math.abs((currentFrame.get(index + 2) & 0xFF) - (previousFrame.get(index + 2) & 0xFF));
                pixelsSampled++;
            }
        }

        if (pixelsSampled == 0) return true;

        double averageDifference = (double) totalDifference / pixelsSampled;

        final double CHANGE_THRESHOLD = 15.0;

        return averageDifference > CHANGE_THRESHOLD;
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
                    ByteBuffer outputBuffer = freeOutputBuffers.take();
                    waitTime = System.nanoTime();
                    outputBuffer.rewind();

                    tfliteInputBuffer.rewind();
                    pixelBuffer.rewind();

                    convertRgbaToRgb(pixelBuffer, tfliteInputBuffer, modelInputWidth, modelInputHeight);

                    aiTime = System.nanoTime();
                    ReflectivePaddingInt8Minimal.applyReflectedPadding(tfliteInputBuffer);
                    tflite.run(tfliteInputBuffer, outputBuffer);
                    calcThreeDFps++;
                    aiTime_end = System.nanoTime();
                    filledOutputBuffers.put(new InferenceResult(pixelBuffer, outputBuffer));
                    pixelBuffer = null;
                } catch (InterruptedException e) {
                    LimeLog.severe("AI inference failed: " + e.getMessage());
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LimeLog.severe("AI inference failed: " + e.getMessage());
                    gpuDelegateFailed.set(true);
                } finally {
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    long waitTimeText = (waitTime - startTime) / 1_000_000;
                    long aitimeText = (aiTime_end - aiTime) / 1_000_000;
                    if (pixelBuffer != null) {
                        freeInputBuffers.offer(pixelBuffer);
                    }
                    Log.d("Stereo3DRenderer", "CalculateTime AiDepthMap: " + duration + " ms " + filledOutputBuffers.remainingCapacity() + " " + waitTimeText + " ms" + "aitime: " + aitimeText);
                }
            }
            isAiRunning.set(false);
        }
    }

    private class AiResultHandling implements Runnable {

        // --- Constants for AI result processing logic ---
        private static final double RENDER_THRESHOLD = 6.0;
        private static final double MINIMUM_THRESHOLD = 2.0;
        private static final double INSTABILITY_THRESHOLD = 10.0;
        private static final double INSTABILITY_THRESHOLD_CONTINUOUS = 2.0;
        private static final double INSTABILITY_THRESHOLD_STRONG = 1.0;
        private static final double MIN_IMAGE_DIFFERENCE = 1.0;
        private static final double MIN_CONTINUOUS_IMAGE_DIFFERENCE = 0.002;
        private static final double IMAGE_DIFFERENCE_MULTIPLIER = 100.0;
        private static final int MAX_CONTINUOUS_MAP_COUNT = 20;

        // --- Constants for temporal smoothing ---
        private static final double SMOOTHING_DIVISOR = 10.0;
        private static final double MAX_SMOOTHING_FACTOR = 0.2;
        private static final double MIN_SMOOTHING_FACTOR = 0.08;

        // --- Member Fields ---
        private final byte[] processedDataArray = new byte[modelInputWidth * modelInputHeight];
        private ByteBuffer previousRawMap = null;
        private Mat previousSmoothedMat;
        private double continouesDifferenceImageCount = 0.0f;
        private double continouesAverageDifference = 0.0f;
        private double continouesDifferenceDepthMapCount = 0.0f;
        private int continouesMapCount = 0;
        private boolean isFirstFrame = true;

        private void resetContinousCounter() {
            continouesDifferenceImageCount = 0.0f;
            continouesDifferenceDepthMapCount = 0.0f;
            continouesMapCount = 0;
        }

        @Override
        public void run() {
            ByteBuffer resultBuffer = createFlatDepthMap();
            InferenceResult result = null;
            boolean takeResult = true;
            boolean takeContinousResult = false;

            while (!Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                long waitTime = System.nanoTime();
                Mat rawMat = null;
                Mat processedMat = null;
                takeResult = true;
                takeContinousResult = false;
                double rawDifference = 0.0;

                try {
                    result = filledOutputBuffers.take();
                    resultBuffer = freeSmoothedBuffers.take();
                    waitTime = System.nanoTime();

                    // Drain the queue to only process the latest result
                    InferenceResult intermediate;
                    while ((intermediate = filledOutputBuffers.poll()) != null) {
                        freeInputBuffers.offer(result.pixelBuffer);
                        freeOutputBuffers.offer(result.rawDepthBuffer);
                        result = intermediate;
                    }
                    ByteBuffer rawDepthBuffer = result.rawDepthBuffer;
                    ByteBuffer currentPixelBuffer = result.pixelBuffer;

                    if (previousRawMap != null) {
                        rawDifference = calculateAverageDifferenceOCV(rawDepthBuffer, previousRawMap, modelInputWidth, modelInputHeight);
                    }

                    if (previousRawMap == null || previousRawMap.capacity() != rawDepthBuffer.capacity()) {
                        previousRawMap = ByteBuffer.allocateDirect(rawDepthBuffer.capacity());
                    }
                    previousRawMap.rewind();
                    rawDepthBuffer.rewind();
                    previousRawMap.put(rawDepthBuffer);

                    if (rawDifference < MINIMUM_THRESHOLD) {
                        takeResult = false;
                    }

                    if (takeResult) {
                        currentPixelBuffer.rewind();
                        double imageDifference = hasFrameChangedSignificantlyOCV(currentPixelBuffer, previousPixelBuffer) * IMAGE_DIFFERENCE_MULTIPLIER;
                        continouesMapCount++;

                        if (imageDifference < MIN_IMAGE_DIFFERENCE) {
                            takeResult = false;
                        }

                        imageDifference = Math.max(imageDifference, 0);
                        double instabilityFactor = rawDifference / Math.max(imageDifference, MIN_IMAGE_DIFFERENCE);

                        if (instabilityFactor > INSTABILITY_THRESHOLD_STRONG && imageDifference <= RENDER_THRESHOLD) {
                            takeResult = false;
                        } else if (instabilityFactor > INSTABILITY_THRESHOLD && imageDifference > RENDER_THRESHOLD) {
                            takeResult = false;
                        }

                        if (takeResult) {
                            Log.d("Stereo3DRenderer", "New SceneChange Result:" + instabilityFactor +
                                    " (DepthDiff: " + rawDifference + ", ImageDiff: " + imageDifference + ")");
                        } else {
                            takeContinousResult = false;
                            continouesDifferenceDepthMapCount += rawDifference;
                            continouesDifferenceImageCount += imageDifference;
                            continouesAverageDifference = continouesDifferenceImageCount / continouesMapCount;
                            instabilityFactor = continouesDifferenceDepthMapCount / Math.max(continouesAverageDifference, MIN_IMAGE_DIFFERENCE);

                            if (instabilityFactor > INSTABILITY_THRESHOLD_CONTINUOUS) {
                                takeContinousResult = true;
                            }
                            if (imageDifference < MIN_CONTINUOUS_IMAGE_DIFFERENCE) {
                                takeContinousResult = false;
                            }
                            if (takeContinousResult) {
                                Log.d("Stereo3DRenderer", "New Continous Result: " + instabilityFactor +
                                        " (DepthDiff: " + continouesDifferenceDepthMapCount + ", ImageDiff: " + continouesDifferenceImageCount + ")");
                            }
                        }

                        if (continouesMapCount > MAX_CONTINUOUS_MAP_COUNT) {
                            resetContinousCounter();
                        }

                        if (takeResult || takeContinousResult) {
                            resetContinousCounter();
                            rawMat = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC1, rawDepthBuffer);
                            processedMat = new Mat();
                            Core.normalize(rawMat, processedMat, 0, 255, Core.NORM_MINMAX);

                            if (isFirstFrame) {
                                previousSmoothedMat = processedMat.clone();
                                isFirstFrame = false;
                            }

                            if (takeContinousResult) {
                                double smoothing = continouesAverageDifference / SMOOTHING_DIVISOR;
                                smoothing = Math.min(smoothing, MAX_SMOOTHING_FACTOR);
                                smoothing = Math.max(smoothing, MIN_SMOOTHING_FACTOR);
                                Core.addWeighted(processedMat, smoothing, previousSmoothedMat, 1.0 - smoothing, 0.0, previousSmoothedMat);
                            } else {
                                previousSmoothedMat = processedMat.clone();
                            }

                            previousSmoothedMat.get(0, 0, processedDataArray);
                            rawDepthBuffer.rewind();
                            rawDepthBuffer.put(processedDataArray);

                            rawDepthBuffer.rewind();
                            resultBuffer.rewind();
                            resultBuffer.put(rawDepthBuffer);

                            rawDepthBuffer.rewind();
                            resultBuffer.rewind();
                            readyToRenderQueue.put(resultBuffer);
                        }
                    }

                    previousPixelBuffer.rewind();
                    previousPixelBuffer.put(currentPixelBuffer);
                } catch (Exception e) {
                    LimeLog.severe("AI exception " + e.getMessage());
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
                        freeOutputBuffers.offer(result.rawDepthBuffer);
                    }
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    long waitTimeText = (waitTime - startTime) / 1_000_000;
                    Log.d("Stereo3DRenderer", "CalculateTime AiResult:    " + duration + " ms" + " " + freeOutputBuffers.remainingCapacity() + " " + waitTimeText + " ms " + takeResult);
                }
            }
            isAiResultHandlingRunning.set(false);
        }
    }
}