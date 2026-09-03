package com.papi.nova.ui

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaComposeBuildConfigurationTest {
    @Test
    fun vulnerableKotlinCachesRemainDisabledAcrossCi() {
        val repositoryRoot = Paths.get("..").toAbsolutePath().normalize()
        val gradleProperties = readText(repositoryRoot.resolve("gradle.properties"))
        val workflows = Files.list(repositoryRoot.resolve(".github/workflows")).use { paths ->
            paths.iterator().asSequence()
                .filter { path ->
                    Files.isRegularFile(path) &&
                        (path.fileName.toString().endsWith(".yml") || path.fileName.toString().endsWith(".yaml"))
                }
                .associate { it.fileName.toString() to readText(it) }
        }

        assertPropertyDisabled(
            gradleProperties,
            "org.gradle.caching",
            "Kotlin 2.3.21 must keep the Gradle build cache disabled"
        )
        assertPropertyDisabled(
            gradleProperties,
            "kapt.incremental.apt",
            "Kotlin 2.3.21 must keep KAPT incremental local-state deserialization disabled"
        )

        workflows.forEach { (name, workflow) ->
            assertFalse(
                "$name must not restore or save the Gradle User Home cache",
                Regex("(?m)^\\s*cache:\\s*['\"]?gradle['\"]?\\s*(?:#.*)?$").containsMatchIn(workflow)
            )
            assertFalse(
                "$name must not enable Gradle's build cache on the command line",
                Regex("(?:^|\\s)--build-cache(?=\\s|$)").containsMatchIn(workflow)
            )
            assertFalse(
                "$name must not override the fail-closed Gradle cache properties",
                workflow.contains("org.gradle.caching") || workflow.contains("kapt.incremental.apt")
            )
            assertFalse(
                "$name must not add a Gradle cache action without extending this fail-closed contract",
                Regex(
                    "(?i)(?:gradle/actions/setup-gradle|gradle/gradle-build-action|" +
                        "actions/cache(?:/restore|/save)?)@"
                ).containsMatchIn(workflow)
            )
            workflow.lineSequence()
                .filter { Regex("(?:^|\\s)(?:\\./)?gradle(?:w)?(?:\\s|$)").containsMatchIn(it) }
                .forEach { invocation ->
                    assertTrue(
                        "$name Gradle invocations must fail closed with --no-build-cache: $invocation",
                        invocation.contains("--no-build-cache")
                    )
                }
        }

        val dependencySubmissionSteps = workflows.flatMap { (name, workflow) ->
            actionSteps(workflow, "gradle/actions/dependency-submission").map { name to it }
        }
        val dependencySubmissionReferences = workflows.values.sumOf { workflow ->
            Regex("(?i)gradle/actions/dependency-submission@").findAll(workflow).count()
        }
        assertTrue("CI must retain dependency graph submission", dependencySubmissionSteps.isNotEmpty())
        assertEquals(
            "every dependency-submission reference must be a directly inspectable action step",
            dependencySubmissionReferences,
            dependencySubmissionSteps.size
        )
        dependencySubmissionSteps.forEach { (name, step) ->
            assertTrue(
                "$name dependency submission must disable its Gradle User Home cache",
                Regex("(?m)^\\s*cache-disabled:\\s*true\\s*$").containsMatchIn(step)
            )
            assertTrue(
                "$name dependency submission must pass --no-build-cache to its Gradle invocation",
                Regex("(?m)^\\s*additional-arguments:.*(?:^|\\s)--no-build-cache(?:\\s|$)")
                    .containsMatchIn(step)
            )
        }
    }

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
        val expectedVersion = "4.1.137.Final"

        assertTrue(
            "root build should keep a single patched Netty version for Gradle and Android test tooling",
            rootBuild.contains("patchedNettyVersion = '$expectedVersion'")
        )
        assertTrue(
            "all project configurations should force Netty transitives onto the patched line",
            rootBuild.contains("details.requested.group == 'io.netty'") &&
                rootBuild.contains("details.useVersion patchedNettyVersion")
        )
        assertTrue(
            "Gradle configuration should validate active Netty constraints from its parsed model",
            rootBuild.contains("buildscript.configurations.classpath.allDependencyConstraints") &&
                rootBuild.contains("Netty buildscript constraints must exactly match")
        )
    }

    @Test
    fun buildDoesNotUseKaptForNewKotlinProcessing() {
        val repositoryRoot = Paths.get("..").toAbsolutePath().normalize()
        val buildFiles = Files.walk(repositoryRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { path ->
                    Files.isRegularFile(path) &&
                        (path.fileName.toString().endsWith(".gradle") || path.fileName.toString().endsWith(".gradle.kts")) &&
                        repositoryRoot.relativize(path).none { segment ->
                            segment.toString() == ".git" ||
                                segment.toString() == ".gradle" ||
                                segment.toString() == "build"
                        }
                }
                .toList()
        }

        buildFiles.forEach { buildFile ->
            assertFalse(
                "Prefer KSP over kapt for future Kotlin annotation processors: ${repositoryRoot.relativize(buildFile)}",
                readText(buildFile).contains("kapt", ignoreCase = true)
            )
        }
    }

    private fun readText(path: Path): String =
        String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private fun assertPropertyDisabled(propertiesText: String, key: String, message: String) {
        val definitionCount = Regex(
            "(?m)^[ \\t\\f]*${Regex.escape(key)}(?=[ \\t\\f:=])"
        ).findAll(propertiesText).count()
        assertEquals("$message with one unambiguous definition", 1, definitionCount)

        val effectiveProperties = Properties().apply {
            load(StringReader(propertiesText))
        }
        assertEquals("$message as its effective Java Properties value", "false", effectiveProperties.getProperty(key))
    }

    private fun actionSteps(workflow: String, action: String): List<String> {
        val lines = workflow.lines()
        return lines.indices
            .filter {
                Regex("(?i)^uses:\\s*['\"]?${Regex.escape(action)}@")
                    .containsMatchIn(lines[it].trimStart().removePrefix("- "))
            }
            .map { usesIndex ->
                val usesLine = lines[usesIndex]
                val usesIndent = usesLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val stepIndent = if (usesLine.trimStart().startsWith("- uses:")) usesIndent else (usesIndent - 2).coerceAtLeast(0)
                var start = usesIndex
                while (start > 0 && !isYamlListItem(lines[start], stepIndent)) start--
                var end = usesIndex + 1
                while (end < lines.size && !isYamlListItem(lines[end], stepIndent)) end++
                lines.subList(start, end).joinToString("\n")
            }
    }

    private fun isYamlListItem(line: String, indent: Int): Boolean =
        line.indexOfFirst { !it.isWhitespace() } == indent && line.trimStart().startsWith("- ")

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
            "tag release verification should require exact equality with three APKs and three checksum assets",
            workflow.contains("Release assets do not match the exact six-file contract") &&
                workflow.contains("\${published_assets[*]}") &&
                workflow.contains("\${expected_assets[*]}")
        )
    }

    @Test
    fun releaseWorkflowWritesPortableChecksumBasenames() {
        val workflow = String(
            Files.readAllBytes(Paths.get("../.github/workflows/build.yml")),
            StandardCharsets.UTF_8
        )

        assertTrue(
            "release checksum generation should derive the public APK basename",
            workflow.contains("apk_name=\"\$(basename \"\$apk\")\"")
        )
        assertTrue(
            "release checksum generation should run inside the asset directory",
            workflow.contains("cd \"\$apk_dir\"")
        )
        assertTrue(
            "release checksum files should contain portable APK basenames instead of runner-absolute paths",
            workflow.contains("sha256sum \"\$apk_name\" > \"\$apk_name.sha256\"")
        )
        assertFalse(
            "release checksum files should not embed the GitHub runner path",
            workflow.contains("sha256sum \"\$apk\" > \"\$apk.sha256\"")
        )
    }

    @Test
    fun baselineProfileGenerationTargetsNovaReleaseJourneys() {
        val rootBuild = String(Files.readAllBytes(Paths.get("../build.gradle")), StandardCharsets.UTF_8)
        val appBuild = String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8)
        val settings = String(Files.readAllBytes(Paths.get("../settings.gradle")), StandardCharsets.UTF_8)
        val baselineBuildPath = Paths.get("../baselineprofile/build.gradle")
        val generatorPath = Paths.get(
            "../baselineprofile/src/main/java/com/papi/nova/baselineprofile/BaselineProfileGenerator.kt"
        )

        assertTrue(
            "root build should declare the Android test plugin for the profile producer module",
            rootBuild.contains("id 'com.android.test' version '9.2.1' apply false")
        )
        assertTrue(
            "root build should declare an AGP 9-compatible Baseline Profile plugin",
            rootBuild.contains("id 'androidx.baselineprofile' version '1.5.0-alpha06' apply false")
        )
        assertTrue(
            "settings should include the profile producer module",
            settings.contains("include ':baselineprofile'")
        )
        assertTrue(
            "app module should consume generated Baseline Profiles for the non-root release variant",
            appBuild.contains("id 'androidx.baselineprofile'") &&
                appBuild.contains("nonRoot_gameRelease") &&
                appBuild.contains("from project(':baselineprofile')")
        )
        assertTrue(
            "baseline profile module build file should exist",
            Files.exists(baselineBuildPath)
        )
        assertTrue(
            "baseline profile generator should exist",
            Files.exists(generatorPath)
        )

        val baselineBuild = String(Files.readAllBytes(baselineBuildPath), StandardCharsets.UTF_8)
        val generator = String(Files.readAllBytes(generatorPath), StandardCharsets.UTF_8)

        assertTrue(
            "baseline profile module should be an Android test producer for the app module",
            baselineBuild.contains("id 'com.android.test'") &&
                baselineBuild.contains("id 'androidx.baselineprofile'") &&
                baselineBuild.contains("targetProjectPath ':app'")
        )
        assertTrue(
            "profile generation should target Nova's non-root release app flavor",
            baselineBuild.contains("nonRoot_game")
        )
        assertTrue(
            "baseline profile module should use an AGP 9-compatible benchmark/profile toolchain",
            baselineBuild.contains("androidx.benchmark:benchmark-macro-junit4:1.5.0-alpha06") &&
                baselineBuild.contains("androidx.test.uiautomator:uiautomator:2.3.0")
        )
        assertTrue(
            "generator should collect startup and reachable library navigation user journeys",
            generator.contains("BaselineProfileRule()") &&
                generator.contains("includeInStartupProfile = true") &&
                generator.contains("By.text(\"Library\")") &&
                generator.contains("fun libraryDetailSurface()") &&
                generator.contains("fun settingsSurface()") &&
                generator.contains("StreamSettings") &&
                !generator.contains("com.papi.nova.ui.NovaLibraryActivity") &&
                !generator.contains("putExtra(\"host\", \"127.0.0.1\")")
        )
    }
}
