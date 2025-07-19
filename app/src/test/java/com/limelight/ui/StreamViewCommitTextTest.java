package com.limelight.ui;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class StreamViewCommitTextTest {
    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void commitText_isForwarded_whenEnabled() {
        StreamView view = new StreamView(ctx);
        StreamView.InputCallbacks cb = Mockito.mock(StreamView.InputCallbacks.class);
        view.setInputCallbacks(cb);
        view.setCommitTextEnabled(true);

        EditorInfo ei = new EditorInfo();
        InputConnection ic = view.onCreateInputConnection(ei);
        assertNotNull("InputConnection should be created when commitText is enabled", ic);

        ic.commitText("hello", 1);

        verify(cb, times(1)).handleCommitText("hello");
    }

    @Test
    public void commitText_notForwarded_whenDisabled() {
        StreamView view = new StreamView(ctx);
        StreamView.InputCallbacks cb = Mockito.mock(StreamView.InputCallbacks.class);
        view.setInputCallbacks(cb);
        view.setCommitTextEnabled(false);

        EditorInfo ei = new EditorInfo();
        InputConnection ic = view.onCreateInputConnection(ei);
        // Should fall back to default behaviour (null or BaseInputConnection without forwarding)
        if (ic != null) {
            ic.commitText("hello", 1);
        }

        verify(cb, never()).handleCommitText(any());
    }
}