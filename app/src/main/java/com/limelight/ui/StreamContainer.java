package com.limelight.ui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;

/**
 * A container that manages different stream display modes and now correctly
 * handles all input callbacks, aspect ratio scaling, and a robust surface lifecycle.
 * It uses SurfaceView for 2D and GLSurfaceView for both 3D modes.
 */
public class StreamContainer extends FrameLayout implements SurfaceHolder.Callback, Stereo3DRenderer.OnSurfaceReadyListener {

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
        boolean handleFocusChange(boolean hasWindowFocus);
    }

    public enum StreamMode {
        MODE_2D,
        MODE_AI_3D
    }

    private final SurfaceView mSurfaceView;
    private final GLSurfaceView mGLSurfaceView;
    private final Stereo3DRenderer mStereoRenderer;

    private Surface mCurrentSurface;
    private Runnable onSurfaceAvailable;
    private StreamMode currentMode = StreamMode.MODE_2D;
    private InputCallbacks mInputCallbacks;
    private boolean commitTextEnabled = false;

    private double desiredAspectRatio;
    private boolean fillDisplay = false;

    private boolean isSurfaceReady = false;

    public StreamContainer(Context context, AttributeSet attrs) {
        super(context, attrs);

        setFocusable(true);
        setFocusableInTouchMode(true);

        LayoutParams childParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        // View for standard 2D mode for maximum performance
        mSurfaceView = new SurfaceView(context);
        mSurfaceView.getHolder().addCallback(this);
        addView(mSurfaceView, childParams);

        // View for both 3D modes, which require custom OpenGL rendering
        mGLSurfaceView = new GLSurfaceView(context);
        mGLSurfaceView.setEGLContextClientVersion(3);
        // Pass 'this' as the listener to the renderer.
        mStereoRenderer = new Stereo3DRenderer(mGLSurfaceView, this, context);
        mGLSurfaceView.setRenderer(mStereoRenderer);
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        addView(mGLSurfaceView, childParams);
    }


    public void setPrefConfig(PreferenceConfiguration prefConfig) {
        mStereoRenderer.setPrefConfig(prefConfig);
    }
    // --- Aspect Ratio and Scaling Logic ---
    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;
        requestLayout();
    }

    public void setFillDisplay(boolean fillDisplay) {
        this.fillDisplay = fillDisplay;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (currentMode != StreamMode.MODE_2D) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Für den 2D-Modus wird die ursprüngliche Logik zur Beibehaltung des Seitenverhältnisses verwendet.
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int measuredHeight, measuredWidth;

        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(heightSize * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(widthSize / desiredAspectRatio);
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(measuredHeight * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(measuredWidth / desiredAspectRatio);
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY);
        measureChildren(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    // --- Input Handling Logic ---

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.mInputCallbacks = callbacks;
    }

    public void setCommitTextEnabled(boolean enabled) {
        this.commitTextEnabled = enabled;
        if (enabled) {
            requestFocus();
        }
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (mInputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mInputCallbacks.handleKeyDown(event)) return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mInputCallbacks.handleKeyUp(event)) return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mInputCallbacks != null) {
            mInputCallbacks.handleFocusChange(hasWindowFocus);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return commitTextEnabled || super.onCheckIsTextEditor();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs);
        }
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                return mInputCallbacks != null && mInputCallbacks.handleCommitText(text) || super.commitText(text, newCursorPosition);
            }
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                return mInputCallbacks != null && mInputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength) || super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }

    // --- Surface and Mode Management ---

    public void setOnSurfaceAvailable(Runnable callback) {
        this.onSurfaceAvailable = callback;
        if (isSurfaceReady && onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    public Surface getSurface() {
        return mCurrentSurface;
    }

    public StreamMode mapIntToStreamMode(int modeIndex) {
        StreamContainer.StreamMode[] modes = StreamContainer.StreamMode.values();
        if (modeIndex >= 0 && modeIndex < modes.length) {
            return modes[modeIndex];
        } else {
            return StreamContainer.StreamMode.MODE_2D;
        }
    }
    public void setRenderMode(int renderMode, boolean isInitializing) {
        setStreamMode(mapIntToStreamMode(renderMode), isInitializing);
    }

    public boolean is3DEnabled() {
        return currentMode == StreamMode.MODE_AI_3D;
    }

    public void setStreamMode(StreamMode mode, boolean isInitializing) {
        if (!isInitializing && currentMode == mode) return;
        currentMode = mode;

        isSurfaceReady = false;
        mCurrentSurface = null;

        mSurfaceView.setVisibility(View.GONE);
        mGLSurfaceView.setVisibility(View.GONE);

        switch (mode) {
            case MODE_2D:
                mSurfaceView.setVisibility(View.VISIBLE);
                if (mSurfaceView.getHolder().getSurface() != null && mSurfaceView.getHolder().getSurface().isValid()) {
                    surfaceChanged(mSurfaceView.getHolder(), 0, mSurfaceView.getWidth(), mSurfaceView.getHeight());
                }
                break;
            case MODE_AI_3D:
                mGLSurfaceView.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void notifySurfaceReady() {
        isSurfaceReady = true;
        if (onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    // --- SurfaceHolder.Callback Implementation (for SurfaceView) ---
    @Override
    public void surfaceCreated(SurfaceHolder holder) {}
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (currentMode == StreamMode.MODE_2D && width > 0 && height > 0) {
            mCurrentSurface = holder.getSurface();
            notifySurfaceReady();
        }
    }
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (currentMode == StreamMode.MODE_2D) {
            isSurfaceReady = false;
            mCurrentSurface = null;
        } else {
            mStereoRenderer.onSurfaceDestroyed();
        }
    }

    @Override
    public void onSurfaceReady(Surface surface) {
        if (currentMode != StreamMode.MODE_2D) {
            mCurrentSurface = surface;
            notifySurfaceReady();
        }
    }

    public void onDestroy() {
        mStereoRenderer.onSurfaceDestroyed();
    }
}
