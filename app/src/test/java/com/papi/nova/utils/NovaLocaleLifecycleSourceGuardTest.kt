package com.papi.nova.utils

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLocaleLifecycleSourceGuardTest {
    @Test
    fun gameAppliesLocaleBeforeInflatingContent() {
        assertLocaleAppliedBeforeFirstContentView("Game.kt", "override fun onCreate")
    }

    @Test
    fun appViewAppliesLocaleBeforeInflatingContent() {
        assertLocaleAppliedBeforeFirstContentView("AppView.kt", "override fun onCreate")
    }

    @Test
    fun pcViewAppliesLocaleBeforeInflatingContent() {
        assertLocaleAppliedBeforeFirstContentView("PcView.kt", "override fun onCreate")
    }

    private fun assertLocaleAppliedBeforeFirstContentView(fileName: String, functionSignature: String) {
        val body = functionBody(File("src/main/java/com/papi/nova/$fileName").readText(), functionSignature)
        val localeIndex = body.indexOf("UiHelper.setLocale(this)")
        val contentViewIndex = body.indexOf("setContentView(")

        assertTrue("$fileName should inflate content in $functionSignature", contentViewIndex >= 0)
        assertTrue(
            "$fileName must call UiHelper.setLocale(this) before setContentView() so selected language survives reconnect/startup UI",
            localeIndex >= 0 && localeIndex < contentViewIndex,
        )
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        assertTrue("Missing function signature: $signature", signatureIndex >= 0)

        val openBrace = source.indexOf('{', signatureIndex)
        assertTrue("Missing function body for: $signature", openBrace >= 0)

        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(openBrace, index + 1)
                    }
                }
            }
        }
        throw AssertionError("Unclosed function body for: $signature")
    }
}
