package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaReleaseMetadataTest {
    private fun repoRoot(): File {
        return generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { candidate -> File(candidate, "app/build.gradle").isFile }
    }

    @Test
    fun versionNameCodeAndPublicReleaseMetadataStayConsistent() {
        val root = repoRoot()
        val build = File(root, "app/build.gradle").readText()
        val changelog = File(root, "CHANGELOG.md").readText()
        val readme = File(root, "README.md").readText()
        val storeNotes = File(
            root,
            "fastlane/metadata/android/en-US/changelogs/35.txt"
        )

        assertTrue(build.contains("versionName \"1.3.3\""))
        assertTrue(build.contains("versionCode = 35"))
        assertTrue(changelog.contains("## 1.3.3 - 2026-07-30"))
        assertTrue(readme.contains("## Latest release: v1.3.3"))
        assertTrue(readme.contains("VersionCode 35"))
        assertFalse(readme.contains("## Latest release: v1.3.2"))
        assertTrue(storeNotes.isFile)
        assertTrue(storeNotes.readText().startsWith("Nova 1.3.3\n"))
    }
}
