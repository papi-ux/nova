package com.papi.nova.ui

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StreamViewCommitTextTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun commitTextIsForwardedWhenEnabled() {
        val view = StreamView(context)
        val callbacks = Mockito.mock(StreamView.InputCallbacks::class.java)
        view.setInputCallbacks(callbacks)
        view.setCommitTextEnabled(true)

        val editorInfo = EditorInfo()
        val inputConnection = view.onCreateInputConnection(editorInfo)
        assertNotNull("InputConnection should be created when commitText is enabled", inputConnection)

        inputConnection!!.commitText("hello", 1)

        verify(callbacks, times(1)).handleCommitText("hello")
    }

    @Test
    fun commitTextIsNotForwardedWhenDisabled() {
        val view = StreamView(context)
        val callbacks = Mockito.mock(StreamView.InputCallbacks::class.java)
        view.setInputCallbacks(callbacks)
        view.setCommitTextEnabled(false)

        val editorInfo = EditorInfo()
        val inputConnection = view.onCreateInputConnection(editorInfo)
        inputConnection?.commitText("hello", 1)

        verify(callbacks, never()).handleCommitText("hello")
    }
}
