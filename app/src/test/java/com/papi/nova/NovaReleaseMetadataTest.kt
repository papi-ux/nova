package com.papi.nova

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaReleaseMetadataTest {
    private fun repoRoot(): File {
        return generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { candidate -> File(candidate, "app/build.gradle").isFile }
    }

    private fun workflowStep(workflow: String, name: String): String {
        val marker = "      - name: $name\n"
        val start = workflow.indexOf(marker)
        require(start >= 0) { "Missing workflow step: $name" }
        val end = workflow.indexOf("\n      - name:", start + marker.length)
            .let { if (it >= 0) it else workflow.length }
        return workflow.substring(start, end)
    }

    @Test
    fun versionNameCodeAndPublicReleaseMetadataStayConsistent() {
        val root = repoRoot()
        val build = File(root, "app/build.gradle").readText()
        val changelog = File(root, "CHANGELOG.md").readText()
        val releaseScript = File(root, "bin/release.sh").readText()
        val releaseWorkflow = File(root, ".github/workflows/build.yml").readText()
        val storeNotes = File(
            root,
            "fastlane/metadata/android/en-US/changelogs/39.txt"
        )
        val storeNotesBody = if (storeNotes.isFile) storeNotes.readText().trimEnd() else ""

        assertTrue(build.contains("versionName \"1.3.7\""))
        assertTrue(build.contains("versionCode = 39"))
        assertTrue(changelog.contains("## 1.3.7 - 2026-08-19"))
        assertTrue(changelog.contains("does not automatically upload a report"))
        assertTrue(releaseWorkflow.contains("python3 scripts/extract_release_notes.py"))
        assertTrue(releaseWorkflow.contains("--notes-file \"\$release_notes\""))
        assertTrue(releaseWorkflow.contains("gh release edit \"\${GITHUB_REF_NAME}\""))
        assertTrue(releaseWorkflow.contains("published_notes="))
        assertTrue(!releaseWorkflow.contains("--generate-notes"))
        assertTrue(releaseScript.contains("-PnovaAbis=arm64-v8a,armeabi-v7a,x86_64"))
        for (asset in listOf(
            "Nova-Android-arm64-v8a.apk",
            "Nova-Android-armeabi-v7a.apk",
            "Nova-Android-x86_64.apk",
        )) {
            assertTrue(releaseScript.contains(asset))
        }
        assertTrue(storeNotes.isFile)
        assertTrue(
            "Google Play release notes must be at most 500 Unicode characters",
            storeNotesBody.codePointCount(0, storeNotesBody.length) <= 500,
        )
        assertTrue(storeNotesBody.startsWith("Nova 1.3.7"))
    }

    @Test
    fun releasePublicationStaysBoundToOneVerifiedSource() {
        val root = repoRoot()
        val workflow = File(root, ".github/workflows/build.yml").readText()
        val orderedSteps = listOf(
            "Verify release tag matches built source",
            "Stage curated GitHub release",
            "Upload GitHub Release assets",
            "Verify GitHub Release assets",
            "Publish verified draft release",
        )
        val positions = orderedSteps.map { workflow.indexOf("      - name: $it\n") }

        assertTrue(positions.all { it >= 0 })
        assertTrue(positions == positions.sorted())
        assertTrue(workflow.split("uses: actions/checkout@v5").size - 1 == 3)
        assertTrue(workflow.split("ref: \${{ github.sha }}").size - 1 == 3)
        assertTrue(workflow.contains("source_commit: \${{ steps.source.outputs.commit }}"))

        val tagGuard = workflowStep(workflow, orderedSteps[0])
        assertTrue(tagGuard.contains("^v[0-9]+\\.[0-9]+\\.[0-9]+\$"))
        assertTrue(tagGuard.contains("EXPECTED_SOURCE_COMMIT: \${{ needs.verify.outputs.source_commit }}"))
        assertTrue(tagGuard.contains("checked_out_commit=\"\$(git rev-parse HEAD)\""))
        assertTrue(tagGuard.contains("git fetch --no-tags --force origin"))
        assertTrue(tagGuard.contains("tag_commit=\"\$(git rev-parse"))
        assertTrue(tagGuard.contains("if [ \"\$tag_commit\" != \"\$EXPECTED_SOURCE_COMMIT\" ]; then"))

        val stage = workflowStep(workflow, orderedSteps[1])
        assertTrue(stage.contains("id: stage-release"))
        assertTrue(stage.contains("--draft"))
        assertTrue(stage.contains("--draft=true"))
        assertTrue(stage.split("--verify-tag").size - 1 == 2)
        assertTrue(stage.split("echo \"publish_draft=true\"").size - 1 == 2)
        assertTrue(!stage.contains("is_draft="))
        assertTrue(stage.contains("published_notes="))
        assertTrue(stage.contains("if [ \"\$published_notes\" != \"\$expected_notes\" ]; then"))
        assertTrue(!stage.contains("gh release upload"))

        val upload = workflowStep(workflow, orderedSteps[2])
        assertTrue(upload.contains("gh release upload"))
        assertTrue(!upload.contains("published_notes="))

        val verify = workflowStep(workflow, orderedSteps[3])
        for (asset in listOf(
            "Nova-Android-arm64-v8a.apk",
            "Nova-Android-arm64-v8a.apk.sha256",
            "Nova-Android-armeabi-v7a.apk",
            "Nova-Android-armeabi-v7a.apk.sha256",
            "Nova-Android-x86_64.apk",
            "Nova-Android-x86_64.apk.sha256",
        )) {
            assertTrue(verify.contains(asset))
        }
        assertTrue(verify.contains("if [ \"\${published_assets[*]}\" != \"\${expected_assets[*]}\" ]; then"))

        val publish = workflowStep(workflow, orderedSteps[4])
        assertTrue(publish.contains("steps.stage-release.outputs.publish_draft == 'true'"))
        assertTrue(publish.contains("--verify-tag --draft=false"))
    }
}
