package com.papi.nova

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.SurfaceHolder
import com.papi.nova.binding.audio.AndroidAudioRenderer
import com.papi.nova.binding.crypto.AndroidCryptoProvider
import com.papi.nova.nvstream.av.audio.AudioRenderer
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.ApertureViewGroup
import com.papi.nova.ui.StreamContainer
import com.papi.nova.ui.StreamView
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinPlatformLeavesMigrationTest {
    @Test
    fun platformLeavesAreKotlinSources() {
        val names = arrayOf(
            "binding/audio/AndroidAudioRenderer",
            "binding/crypto/AndroidCryptoProvider",
            "ui/StreamView",
            "ui/StreamContainer",
            "ui/ApertureViewGroup"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun platformLeavesKeepJavaCompatibleApis() {
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!
        val doubleType = Double::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!

        assertTrue(AudioRenderer::class.java.isAssignableFrom(AndroidAudioRenderer::class.java))
        AndroidAudioRenderer::class.java.getConstructor(Context::class.java, booleanType)
        AndroidAudioRenderer::class.java.getField("hapticEngine")
        AndroidAudioRenderer::class.java.getMethod("setup", MoonBridge.AudioConfiguration::class.java, intType, intType)
        AndroidAudioRenderer::class.java.getMethod("playDecodedAudio", ShortArray::class.java)
        AndroidAudioRenderer::class.java.getMethod("start")
        AndroidAudioRenderer::class.java.getMethod("stop")
        AndroidAudioRenderer::class.java.getMethod("cleanup")

        assertTrue(LimelightCryptoProvider::class.java.isAssignableFrom(AndroidCryptoProvider::class.java))
        AndroidCryptoProvider::class.java.getConstructor(Context::class.java)
        assertTrue(
            X509Certificate::class.java.isAssignableFrom(
                AndroidCryptoProvider::class.java.getMethod("getClientCertificate").returnType
            )
        )
        assertTrue(
            PrivateKey::class.java.isAssignableFrom(
                AndroidCryptoProvider::class.java.getMethod("getClientPrivateKey").returnType
            )
        )
        AndroidCryptoProvider::class.java.getMethod("getPemEncodedClientCertificate")
        AndroidCryptoProvider::class.java.getMethod("encodeBase64String", ByteArray::class.java)

        StreamView::class.java.getConstructor(Context::class.java)
        StreamView::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        StreamView::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType)
        StreamView::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType, intType)
        StreamView::class.java.getMethod("setDesiredAspectRatio", doubleType)
        StreamView::class.java.getMethod("setInputCallbacks", StreamView.InputCallbacks::class.java)
        StreamView::class.java.getMethod("setFillDisplay", booleanType)
        StreamView::class.java.getMethod("setCommitTextEnabled", booleanType)
        StreamView.InputCallbacks::class.java.getMethod("handleKeyDown", KeyEvent::class.java)
        StreamView.InputCallbacks::class.java.getMethod("handleCommitText", CharSequence::class.java)

        StreamContainer::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        StreamContainer::class.java.getMethod("init", Game::class.java, PreferenceConfiguration::class.java)
        StreamContainer::class.java.getMethod("setDesiredAspectRatio", doubleType)
        StreamContainer::class.java.getMethod("setFillDisplay", booleanType)
        StreamContainer::class.java.getMethod("setInputCallbacks", StreamContainer.InputCallbacks::class.java)
        StreamContainer::class.java.getMethod("setCommitTextEnabled", booleanType)
        StreamContainer::class.java.getMethod("setOnSurfaceAvailable", Runnable::class.java)
        StreamContainer::class.java.getMethod("getSurface")
        StreamContainer::class.java.getMethod("getSurfaceView")
        StreamContainer::class.java.getMethod("surfaceCreated", SurfaceHolder::class.java)
        StreamContainer::class.java.getMethod("surfaceChanged", SurfaceHolder::class.java, intType, intType, intType)
        StreamContainer::class.java.getMethod("surfaceDestroyed", SurfaceHolder::class.java)
        StreamContainer::class.java.getMethod("onDestroy")

        ApertureViewGroup::class.java.getConstructor(Context::class.java)
        ApertureViewGroup::class.java.getConstructor(Context::class.java, AttributeSet::class.java)
        ApertureViewGroup::class.java.getConstructor(Context::class.java, AttributeSet::class.java, intType)
        ApertureViewGroup::class.java.getMethod("getCurrentSpeed")
        ApertureViewGroup::class.java.getMethod("setCurrentSpeed", floatType)
    }
}
