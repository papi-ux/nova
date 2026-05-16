package com.papi.nova.ui

import android.content.ClipData
import android.content.Context
import android.text.ClipboardManager
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class StreamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes) {
    private var desiredAspectRatio = 0.0
    private var inputCallbacks: InputCallbacks? = null
    private var fillDisplay = false
    private var commitTextEnabled = false

    fun setDesiredAspectRatio(aspectRatio: Double) {
        desiredAspectRatio = aspectRatio
    }

    fun setInputCallbacks(callbacks: InputCallbacks?) {
        inputCallbacks = callbacks
    }

    fun setFillDisplay(fillDisplay: Boolean) {
        this.fillDisplay = fillDisplay
    }

    fun setCommitTextEnabled(enabled: Boolean) {
        commitTextEnabled = enabled
        if (enabled) {
            isFocusableInTouchMode = true
            requestFocus()
        }
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
        inputCallbacks?.let { callbacks ->
            if (!callbacks.isOnExternalDisplay()) {
                callbacks.handleFocusChange(hasWindowFocus)
            }
        }
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
                if (inputCallbacks?.handleCommitText(text) == true) {
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (inputCallbacks?.handleDeleteSurroundingText(beforeLength, afterLength) == true) {
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    interface InputCallbacks {
        fun handleKeyUp(event: KeyEvent): Boolean
        fun handleKeyDown(event: KeyEvent): Boolean
        fun handleCommitText(text: CharSequence): Boolean
        fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
        fun handleFocusChange(hasWindowFocus: Boolean): Boolean
        fun isOnExternalDisplay(): Boolean
    }
}
