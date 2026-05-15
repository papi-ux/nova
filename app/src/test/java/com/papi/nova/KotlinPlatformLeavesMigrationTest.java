package com.papi.nova;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.KeyEvent;

import com.papi.nova.binding.audio.AndroidAudioRenderer;
import com.papi.nova.binding.crypto.AndroidCryptoProvider;
import com.papi.nova.nvstream.av.audio.AudioRenderer;
import com.papi.nova.nvstream.http.LimelightCryptoProvider;
import com.papi.nova.nvstream.jni.MoonBridge;
import com.papi.nova.ui.ApertureViewGroup;
import com.papi.nova.ui.StreamContainer;
import com.papi.nova.ui.StreamView;

import org.junit.Test;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class KotlinPlatformLeavesMigrationTest {
    @Test
    public void platformLeavesAreKotlinSources() {
        String[] names = {
                "binding/audio/AndroidAudioRenderer",
                "binding/crypto/AndroidCryptoProvider",
                "ui/StreamView",
                "ui/StreamContainer",
                "ui/ApertureViewGroup"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void platformLeavesKeepJavaCompatibleApis() throws Exception {
        assertTrue(AudioRenderer.class.isAssignableFrom(AndroidAudioRenderer.class));
        AndroidAudioRenderer.class.getConstructor(Context.class, boolean.class);
        AndroidAudioRenderer.class.getField("hapticEngine");
        AndroidAudioRenderer.class.getMethod("setup", MoonBridge.AudioConfiguration.class, int.class, int.class);
        AndroidAudioRenderer.class.getMethod("playDecodedAudio", short[].class);
        AndroidAudioRenderer.class.getMethod("start");
        AndroidAudioRenderer.class.getMethod("stop");
        AndroidAudioRenderer.class.getMethod("cleanup");

        assertTrue(LimelightCryptoProvider.class.isAssignableFrom(AndroidCryptoProvider.class));
        AndroidCryptoProvider.class.getConstructor(Context.class);
        assertTrue(X509Certificate.class.isAssignableFrom(
                AndroidCryptoProvider.class.getMethod("getClientCertificate").getReturnType()));
        assertTrue(PrivateKey.class.isAssignableFrom(
                AndroidCryptoProvider.class.getMethod("getClientPrivateKey").getReturnType()));
        AndroidCryptoProvider.class.getMethod("getPemEncodedClientCertificate");
        AndroidCryptoProvider.class.getMethod("encodeBase64String", byte[].class);

        StreamView.class.getConstructor(Context.class);
        StreamView.class.getConstructor(Context.class, AttributeSet.class);
        StreamView.class.getConstructor(Context.class, AttributeSet.class, int.class);
        StreamView.class.getConstructor(Context.class, AttributeSet.class, int.class, int.class);
        StreamView.class.getMethod("setDesiredAspectRatio", double.class);
        StreamView.class.getMethod("setInputCallbacks", StreamView.InputCallbacks.class);
        StreamView.class.getMethod("setFillDisplay", boolean.class);
        StreamView.class.getMethod("setCommitTextEnabled", boolean.class);
        StreamView.InputCallbacks.class.getMethod("handleKeyDown", KeyEvent.class);
        StreamView.InputCallbacks.class.getMethod("handleCommitText", CharSequence.class);

        StreamContainer.class.getConstructor(Context.class, AttributeSet.class);
        StreamContainer.class.getMethod("init", Game.class, com.papi.nova.preferences.PreferenceConfiguration.class);
        StreamContainer.class.getMethod("setDesiredAspectRatio", double.class);
        StreamContainer.class.getMethod("setFillDisplay", boolean.class);
        StreamContainer.class.getMethod("setInputCallbacks", StreamContainer.InputCallbacks.class);
        StreamContainer.class.getMethod("setCommitTextEnabled", boolean.class);
        StreamContainer.class.getMethod("setOnSurfaceAvailable", Runnable.class);
        StreamContainer.class.getMethod("getSurface");
        StreamContainer.class.getMethod("getSurfaceView");
        StreamContainer.class.getMethod("surfaceCreated", SurfaceHolder.class);
        StreamContainer.class.getMethod("surfaceChanged", SurfaceHolder.class, int.class, int.class, int.class);
        StreamContainer.class.getMethod("surfaceDestroyed", SurfaceHolder.class);
        StreamContainer.class.getMethod("onDestroy");

        ApertureViewGroup.class.getConstructor(Context.class);
        ApertureViewGroup.class.getConstructor(Context.class, AttributeSet.class);
        ApertureViewGroup.class.getConstructor(Context.class, AttributeSet.class, int.class);
        ApertureViewGroup.class.getMethod("getCurrentSpeed");
        ApertureViewGroup.class.getMethod("setCurrentSpeed", float.class);
    }
}
