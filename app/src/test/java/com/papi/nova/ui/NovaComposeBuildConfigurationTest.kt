package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
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

    @Test
    fun rootTestAggregationUsesLazyTaskRegistration() {
        val rootBuild = String(Files.readAllBytes(Paths.get("../build.gradle")), StandardCharsets.UTF_8)

        assertFalse(
            "root test aggregation should not wait for projectsEvaluated",
            rootBuild.contains("gradle.projectsEvaluated")
        )
        assertTrue(
            "root test aggregation should register the test task lazily",
            rootBuild.contains("def testAllUnitTests = tasks.register('test')")
        )
        assertTrue(
            "root test aggregation should wire subproject unit-test tasks without realizing them early",
            rootBuild.contains("tasks.matching { it.name.endsWith('UnitTest') }.configureEach")
        )
    }

    @Test
    fun releaseResourceShrinkingUsesGradleAssignmentSyntax() {
        val appBuild = String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8)

        assertTrue(
            "release resource shrinking should use Gradle 10-compatible assignment syntax",
            appBuild.contains("shrinkResources = true")
        )
        assertFalse(
            "release resource shrinking should not use deprecated Groovy space assignment",
            appBuild.contains("shrinkResources true")
        )
    }

    @Test
    fun defaultReleaseSplitsIncludeChromecastArm32Abi() {
        val appBuild = String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8)
        val configuredAbis = Regex("""project\.findProperty\("novaAbis"\) \?: "([^"]+)"""")
            .find(appBuild)
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?: emptyList()

        assertTrue(
            "default release ABI splits should include arm64, 32-bit ARM for Chromecast/Google TV, and x86_64",
            configuredAbis.containsAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        )
    }

    @Test
    fun releaseWorkflowPublishesChromecastArm32Asset() {
        val workflow = String(
            Files.readAllBytes(Paths.get("../.github/workflows/build.yml")),
            StandardCharsets.UTF_8
        )

        assertTrue(
            "release workflow should map the 32-bit ARM split to a stable public asset name",
            workflow.contains("""*armeabi-v7a*) abi="armeabi-v7a" ;;""")
        )
        assertTrue(
            "tag release verification should require the 32-bit ARM public APK",
            workflow.contains("Nova-Android-armeabi-v7a.apk")
        )
        assertTrue(
            "tag release verification should expect three APKs and three checksum assets",
            workflow.contains("Expected at least 6 release assets")
        )
    }
}
