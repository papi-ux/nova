package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaComposeBuildConfigurationTest {
    @Test
    fun gradleEnablesComposeWithKotlinCompilerPluginAndBom() {
        val rootBuild = String(Files.readAllBytes(Paths.get("../build.gradle")), StandardCharsets.UTF_8)
        val appBuild = String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8)
        val gradleProperties = String(Files.readAllBytes(Paths.get("../gradle.properties")), StandardCharsets.UTF_8)

        assertTrue(
            "root build should declare the Compose compiler plugin",
            rootBuild.contains("id 'org.jetbrains.kotlin.plugin.compose'")
        )
        assertTrue(
            "AGP 9 built-in Kotlin should remain enabled",
            gradleProperties.contains("android.builtInKotlin=true") &&
                !appBuild.contains("id 'org.jetbrains.kotlin.android'")
        )
        assertTrue(
            "app module should apply the Compose compiler plugin",
            appBuild.contains("id 'org.jetbrains.kotlin.plugin.compose'")
        )
        assertTrue(
            "app module should enable Compose",
            appBuild.contains("compose = true") || appBuild.contains("compose true")
        )
        assertTrue(
            "app module should use the newest Compose BOM compatible with minSdk 21",
            appBuild.contains("androidx.compose:compose-bom:2025.11.01")
        )
        assertTrue(
            "app module should include Material3 Compose",
            appBuild.contains("androidx.compose.material3:material3")
        )
        assertTrue(
            "app module should include Compose UI tests",
            appBuild.contains("androidx.compose.ui:ui-test-junit4")
        )
    }

    @Test
    fun gradlePinsNettyForToolingDependencyAlerts() {
        val rootBuild = String(Files.readAllBytes(Paths.get("../build.gradle")), StandardCharsets.UTF_8)

        assertTrue(
            "root build should keep a single patched Netty version for Gradle and Android test tooling",
            rootBuild.contains("patchedNettyVersion = '4.1.133.Final'")
        )
        assertTrue(
            "all project configurations should force Netty transitives onto the patched line",
            rootBuild.contains("details.requested.group == 'io.netty'") &&
                rootBuild.contains("details.useVersion patchedNettyVersion")
        )
        assertTrue(
            "the existing buildscript classpath constraints should still cover settings/build-tool Netty transitives",
            rootBuild.contains("classpath('io.netty:netty-codec:4.1.133.Final')") &&
                rootBuild.contains("classpath('io.netty:netty-codec-http:4.1.133.Final')") &&
                rootBuild.contains("classpath('io.netty:netty-codec-http2:4.1.133.Final')") &&
                rootBuild.contains("classpath('io.netty:netty-handler-proxy:4.1.133.Final')")
        )
    }
}
