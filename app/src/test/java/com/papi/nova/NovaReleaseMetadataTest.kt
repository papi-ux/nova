package com.papi.nova

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaReleaseMetadataTest {
    private fun repoRoot(): File {
        return generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { candidate -> File(candidate, "app/build.gradle").isFile }
    }

    private fun workflowJob(workflow: String, name: String): String {
        val marker = "  $name:\n"
        val start = workflow.indexOf(marker)
        require(start >= 0) { "Missing workflow job: $name" }
        val nextJob = Regex("(?m)^  [A-Za-z0-9_-]+:\\s*$")
            .find(workflow, start + marker.length)
        val end = nextJob?.range?.first ?: workflow.length
        return workflow.substring(start, end)
    }

    private fun workflowStep(job: String, name: String): String {
        val marker = "      - name: $name\n"
        val start = job.indexOf(marker)
        require(start >= 0) { "Missing workflow step: $name" }
        val end = job.indexOf("\n      - name:", start + marker.length)
            .let { if (it >= 0) it else job.length }
        return job.substring(start, end)
    }

    private fun workflowRunLines(step: String): List<String> {
        val blockMarker = "        run: |\n"
        val blockStart = step.indexOf(blockMarker)
        if (blockStart >= 0) {
            return step.substring(blockStart + blockMarker.length)
                .lineSequence()
                .takeWhile { it.isBlank() || it.startsWith("          ") }
                .map { if (it.length >= 10) it.substring(10).trim() else "" }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }

        val scalar = Regex("(?m)^        run: (.+)$").find(step)
            ?: error("Missing workflow run command")
        return listOf(scalar.groupValues[1].trim())
    }

    private fun assertOrdered(lines: List<String>, expected: List<String>) {
        var cursor = 0
        for (expectedLine in expected) {
            val relativePosition = lines.subList(cursor, lines.size).indexOf(expectedLine)
            assertTrue(
                "Missing or out-of-order executable release command: $expectedLine",
                relativePosition >= 0,
            )
            cursor += relativePosition + 1
        }
    }

    private fun assertConsecutive(lines: List<String>, expected: List<String>) {
        val width = expected.size
        assertTrue(
            "Missing fail-closed executable block: $expected",
            lines.windowed(width).any { it == expected },
        )
    }

    private fun assertNoHeredoc(lines: List<String>, context: String) {
        assertTrue("$context must not use heredoc payloads as contract evidence", lines.none {
            it.contains("<<")
        })
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
        val buildJob = workflowJob(workflow, "build")
        val orderedSteps = listOf(
            "Verify release tag matches built source",
            "Stage curated GitHub release",
            "Upload GitHub Release assets",
            "Verify GitHub Release assets",
            "Publish verified draft release",
        )
        val positions = orderedSteps.map { buildJob.indexOf("      - name: $it\n") }

        assertTrue(positions.all { it >= 0 })
        assertTrue(positions == positions.sorted())
        assertTrue(workflow.lines().count { it == "      - uses: actions/checkout@v5" } == 3)
        assertTrue(workflow.lines().count { it == "          ref: \${{ github.sha }}" } == 3)
        assertTrue(workflow.lines().count {
            it == "      source_commit: \${{ steps.source.outputs.commit }}"
        } == 1)

        val tagGuard = workflowStep(buildJob, orderedSteps[0])
        val tagGuardLines = workflowRunLines(tagGuard)
        assertNoHeredoc(tagGuardLines, "Release tag guard")
        assertTrue(tagGuard.lines().any {
            it == "          EXPECTED_SOURCE_COMMIT: \${{ needs.verify.outputs.source_commit }}"
        })
        assertOrdered(tagGuardLines, listOf(
            "if [[ ! \"\${GITHUB_REF_NAME}\" =~ ^v[0-9]+\\.[0-9]+\\.[0-9]+\$ ]]; then",
            "checked_out_commit=\"\$(git rev-parse HEAD)\"",
            "git fetch --no-tags --force origin \\",
            "tag_commit=\"\$(git rev-parse \"refs/tags/\${GITHUB_REF_NAME}^{commit}\")\"",
            "if [ \"\$tag_commit\" != \"\$EXPECTED_SOURCE_COMMIT\" ]; then",
        ))
        assertConsecutive(tagGuardLines, listOf(
            "if [[ ! \"\${GITHUB_REF_NAME}\" =~ ^v[0-9]+\\.[0-9]+\\.[0-9]+\$ ]]; then",
            "echo \"Release tag must match vMAJOR.MINOR.PATCH: \${GITHUB_REF_NAME}\" >&2",
            "exit 1",
            "fi",
        ))
        assertConsecutive(tagGuardLines, listOf(
            "if [ \"\$checked_out_commit\" != \"\$EXPECTED_SOURCE_COMMIT\" ]; then",
            "echo \"Release checkout \$checked_out_commit does not match verified source \$EXPECTED_SOURCE_COMMIT\" >&2",
            "exit 1",
            "fi",
        ))
        assertConsecutive(tagGuardLines, listOf(
            "if [ \"\$tag_commit\" != \"\$EXPECTED_SOURCE_COMMIT\" ]; then",
            "echo \"Release tag \${GITHUB_REF_NAME} moved to \$tag_commit; expected \$EXPECTED_SOURCE_COMMIT\" >&2",
            "exit 1",
            "fi",
        ))

        val stage = workflowStep(buildJob, orderedSteps[1])
        val stageLines = workflowRunLines(stage)
        assertNoHeredoc(stageLines, "Release staging")
        assertTrue(stage.lines().any { it == "        id: stage-release" })
        assertOrdered(stageLines, listOf(
            "expected_asset_names=(",
            "release_assets=()",
            "for asset_name in \"\${expected_asset_names[@]}\"; do",
            "if [ \"\${local_asset_names[*]}\" != \"\${expected_asset_names[*]}\" ]; then",
            "gh release create \"\${GITHUB_REF_NAME}\" \\",
            "--draft \\",
            "--verify-tag \\",
            "echo \"publish_draft=true\" >> \"\$GITHUB_OUTPUT\"",
            "gh release edit \"\${GITHUB_REF_NAME}\" \\",
            "--verify-tag \\",
            "--draft=true \\",
            "echo \"publish_draft=true\" >> \"\$GITHUB_OUTPUT\"",
            "published_notes=\"\$(gh release view \"\${GITHUB_REF_NAME}\" --json body --jq .body)\"",
            "if [ \"\$published_notes\" != \"\$expected_notes\" ]; then",
        ))
        assertTrue(stageLines.count { it == "--verify-tag \\" } == 2)
        assertTrue(stageLines.count {
            it == "echo \"publish_draft=true\" >> \"\$GITHUB_OUTPUT\""
        } == 2)
        assertTrue(stageLines.none { it.contains("is_draft=") })
        assertTrue(stageLines.none { it.startsWith("gh release upload ") })
        assertTrue(stageLines.count {
            it == "gh release create \"\${GITHUB_REF_NAME}\" \\"
        } == 1)
        assertTrue(stageLines.count {
            it == "gh release edit \"\${GITHUB_REF_NAME}\" \\"
        } == 1)
        val stageReleaseMutations = stageLines.filter {
            Regex("\\bgh\\s+release\\s+(create|edit|upload|delete-asset)\\b")
                .containsMatchIn(it)
        }
        assertTrue(stageReleaseMutations == listOf(
            "gh release create \"\${GITHUB_REF_NAME}\" \\",
            "gh release edit \"\${GITHUB_REF_NAME}\" \\",
        ))
        assertConsecutive(stageLines, listOf(
            "expected_asset_names=(",
            "Nova-Android-arm64-v8a.apk",
            "Nova-Android-arm64-v8a.apk.sha256",
            "Nova-Android-armeabi-v7a.apk",
            "Nova-Android-armeabi-v7a.apk.sha256",
            "Nova-Android-x86_64.apk",
            "Nova-Android-x86_64.apk.sha256",
            ")",
        ))
        assertConsecutive(stageLines, listOf(
            "if [ ! -f \"\$release_asset\" ]; then",
            "echo \"Missing release asset: \$release_asset\" >&2",
            "exit 1",
            "fi",
        ))
        assertConsecutive(stageLines, listOf(
            "if [ \"\${local_asset_names[*]}\" != \"\${expected_asset_names[*]}\" ]; then",
            "echo \"Local release assets do not match the exact six-file contract\" >&2",
            "printf 'expected: %s\\n' \"\${expected_asset_names[*]}\" >&2",
            "printf 'local: %s\\n' \"\${local_asset_names[*]}\" >&2",
            "exit 1",
            "fi",
        ))
        assertConsecutive(stageLines, listOf(
            "if [ \"\$published_notes\" != \"\$expected_notes\" ]; then",
            "echo \"Published release notes do not match CHANGELOG.md\" >&2",
            "exit 1",
            "fi",
        ))

        val upload = workflowStep(buildJob, orderedSteps[2])
        val uploadLines = workflowRunLines(upload)
        assertNoHeredoc(uploadLines, "Release asset upload")
        assertConsecutive(uploadLines, listOf(
            "expected_asset_names=(",
            "Nova-Android-arm64-v8a.apk",
            "Nova-Android-arm64-v8a.apk.sha256",
            "Nova-Android-armeabi-v7a.apk",
            "Nova-Android-armeabi-v7a.apk.sha256",
            "Nova-Android-x86_64.apk",
            "Nova-Android-x86_64.apk.sha256",
            ")",
        ))
        assertTrue(uploadLines.contains(
            "gh release upload \"\${GITHUB_REF_NAME}\" \"\${release_assets[@]}\" --clobber"
        ))
        assertTrue(uploadLines.filter {
            Regex("\\bgh\\s+release\\s+(create|edit|upload|delete-asset)\\b")
                .containsMatchIn(it)
        } == listOf(
            "gh release upload \"\${GITHUB_REF_NAME}\" \"\${release_assets[@]}\" --clobber"
        ))
        assertTrue(uploadLines.none { it.contains("published_notes=") })

        val verify = workflowStep(buildJob, orderedSteps[3])
        val verifyLines = workflowRunLines(verify)
        assertNoHeredoc(verifyLines, "Release asset verification")
        val exactAssetNames = listOf(
            "Nova-Android-arm64-v8a.apk",
            "Nova-Android-arm64-v8a.apk.sha256",
            "Nova-Android-armeabi-v7a.apk",
            "Nova-Android-armeabi-v7a.apk.sha256",
            "Nova-Android-x86_64.apk",
            "Nova-Android-x86_64.apk.sha256",
        )
        for (asset in exactAssetNames) {
            assertTrue(verifyLines.contains(asset))
        }
        assertConsecutive(
            verifyLines,
            listOf("expected_assets=(") + exactAssetNames + listOf(")"),
        )
        assertTrue(verifyLines.contains(
            "if [ \"\${published_assets[*]}\" != \"\${expected_assets[*]}\" ]; then"
        ))
        assertConsecutive(verifyLines, listOf(
            "if [ \"\${published_assets[*]}\" != \"\${expected_assets[*]}\" ]; then",
            "echo \"Release assets do not match the exact six-file contract on \${GITHUB_REF_NAME}\" >&2",
            "printf 'expected: %s\\n' \"\${expected_assets[*]}\" >&2",
            "printf 'published: %s\\n' \"\${published_assets[*]}\" >&2",
            "exit 1",
            "fi",
        ))

        val publish = workflowStep(buildJob, orderedSteps[4])
        assertTrue(publish.lines().any {
            it == "        if: startsWith(github.ref, 'refs/tags/v') && " +
                "steps.stage-release.outputs.publish_draft == 'true'"
        })
        assertTrue(workflowRunLines(publish) == listOf(
            "gh release edit \"\${GITHUB_REF_NAME}\" --verify-tag --draft=false"
        ))
    }
}
