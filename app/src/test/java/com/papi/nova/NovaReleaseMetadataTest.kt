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
            "fastlane/metadata/android/en-US/changelogs/37.txt"
        )
        val storeNotesBody = storeNotes.readText().trimEnd()

        assertTrue(build.contains("versionName \"1.3.5\""))
        assertTrue(build.contains("versionCode = 37"))
        assertTrue(changelog.contains("## 1.3.5 - 2026-08-08"))
        assertTrue(readme.contains("## Latest release: v1.3.5"))
        assertTrue(readme.contains("VersionCode 37"))
        assertFalse(readme.contains("## Latest release: v1.3.4"))
        assertTrue(storeNotes.isFile)
        assertTrue(
            "Google Play release notes must be at most 500 Unicode characters",
            storeNotesBody.codePointCount(0, storeNotesBody.length) <= 500,
        )
        assertTrue(storeNotesBody.startsWith("Nova 1.3.5"))
    }
}
