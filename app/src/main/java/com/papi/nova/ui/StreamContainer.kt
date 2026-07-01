package com.papi.nova.ui

import android.content.Context
import android.graphics.PixelFormat
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.preferences.PreferenceConfiguration

class StreamContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), SurfaceHolder.Callback {
    interface InputCallbacks {
        fun handleKeyUp(event: KeyEvent): Boolean
        fun handleKeyDown(event: KeyEvent): Boolean
        fun handleCommitText(text: CharSequence): Boolean
        fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
        fun handleFocusChange(hasWindowFocus: Boolean): Boolean
    }

    private var game: Game? = null
    private var surfaceView: SurfaceView? = null
    private var currentSurface: Surface? = null
    private var onSurfaceAvailable: Runnable? = null
    private var inputCallbacks: InputCallbacks? = null
    private var commitTextEnabled = false
    private var desiredAspectRatio = 0.0
    private var fillDisplay = false
    private var isSurfaceReady = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.nova_stream_surface_accessibility_label)
    }

    fun init(game: Game, prefConfig: PreferenceConfiguration) {
        if (this.game != null) {
            return
        }

        this.game = game
        isSurfaceReady = false
        currentSurface = null

        val childParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        val child = SurfaceView(context)
        child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceView = child
        addView(child, childParams)

        child.holder.addCallback(this)
        val holderSurface = child.holder.surface
        if (holderSurface != null && holderSurface.isValid) {
            surfaceChanged(child.holder, PixelFormat.RGBA_8888, child.width, child.height)
        }
    }

    fun setDesiredAspectRatio(aspectRatio: Double) {
        desiredAspectRatio = aspectRatio
        requestLayout()
    }

    fun setFillDisplay(fillDisplay: Boolean) {
        this.fillDisplay = fillDisplay
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (desiredAspectRatio == 0.0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight: Int
        val measuredWidth: Int
        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize
                measuredWidth = (heightSize * desiredAspectRatio).toInt()
            } else {
                measuredWidth = widthSize
                measuredHeight = (widthSize / desiredAspectRatio).toInt()
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize
                measuredWidth = (measuredHeight * desiredAspectRatio).toInt()
            } else {
                measuredWidth = widthSize
                measuredHeight = (measuredWidth / desiredAspectRatio).toInt()
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
        val childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        measureChildren(childWidthMeasureSpec, childHeightMeasureSpec)
    }

    fun setInputCallbacks(callbacks: InputCallbacks?) {
        inputCallbacks = callbacks
    }

    fun setCommitTextEnabled(enabled: Boolean) {
        commitTextEnabled = enabled
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        inputCallbacks?.let { callbacks ->
            if (event.action == KeyEvent.ACTION_DOWN && callbacks.handleKeyDown(event)) {
                return true
            } else if (event.action == KeyEvent.ACTION_UP && callbacks.handleKeyUp(event)) {
                return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        inputCallbacks?.handleFocusChange(hasWindowFocus)
    }

    override fun onCheckIsTextEditor(): Boolean = commitTextEnabled || super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs)
        }

        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                return inputCallbacks?.handleCommitText(text) == true ||
                    super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                return inputCallbacks?.handleDeleteSurroundingText(beforeLength, afterLength) == true ||
                    super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    fun setOnSurfaceAvailable(callback: Runnable?) {
        onSurfaceAvailable = callback
        if (isSurfaceReady && onSurfaceAvailable != null) {
            onSurfaceAvailable?.run()
        }
    }

    fun getSurface(): Surface? = currentSurface

    fun getSurfaceView(): SurfaceView? = surfaceView

    private fun notifySurfaceReady() {
        isSurfaceReady = true
        onSurfaceAvailable?.run()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        game!!.surfaceCreated(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            currentSurface = holder.surface
            notifySurfaceReady()
        }
        game!!.surfaceChanged(holder, format, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceReady = false
        currentSurface = null
        game!!.surfaceDestroyed(holder)
    }

    fun onDestroy() {
    }
}
