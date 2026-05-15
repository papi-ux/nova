package com.papi.nova.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout

class ExternalControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var inputCallbacks: InputCallbacks? = null

    private var commitTextEnabled = false

    fun setInputCallbacks(callbacks: InputCallbacks?) {
        inputCallbacks = callbacks
    }

    fun setCommitTextEnabled(enabled: Boolean) {
        commitTextEnabled = enabled
        if (enabled) {
            isFocusableInTouchMode = true
            requestFocus()
        }
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

    override fun onCheckIsTextEditor(): Boolean =
        commitTextEnabled || super.onCheckIsTextEditor()

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
    }
}
