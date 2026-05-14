package com.papi.nova.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class NovaComposeBuildConfigurationTest {
    @Test
    public void gradleEnablesComposeWithKotlinCompilerPluginAndBom() throws Exception {
        String rootBuild = new String(
                Files.readAllBytes(Paths.get("../build.gradle")),
                StandardCharsets.UTF_8);
        String appBuild = new String(
                Files.readAllBytes(Paths.get("build.gradle")),
                StandardCharsets.UTF_8);
        String gradleProperties = new String(
                Files.readAllBytes(Paths.get("../gradle.properties")),
                StandardCharsets.UTF_8);

        assertTrue("root build should declare the Compose compiler plugin",
                rootBuild.contains("id 'org.jetbrains.kotlin.plugin.compose'"));
        assertTrue("AGP 9 built-in Kotlin should remain enabled",
                gradleProperties.contains("android.builtInKotlin=true") &&
                        !appBuild.contains("id 'org.jetbrains.kotlin.android'"));
        assertTrue("app module should apply the Compose compiler plugin",
                appBuild.contains("id 'org.jetbrains.kotlin.plugin.compose'"));
        assertTrue("app module should enable Compose",
                appBuild.contains("compose = true") || appBuild.contains("compose true"));
        assertTrue("app module should use the newest Compose BOM compatible with minSdk 21",
                appBuild.contains("androidx.compose:compose-bom:2025.11.01"));
        assertTrue("app module should include Material3 Compose",
                appBuild.contains("androidx.compose.material3:material3"));
        assertTrue("app module should include Compose UI tests",
                appBuild.contains("androidx.compose.ui:ui-test-junit4"));
    }
}
