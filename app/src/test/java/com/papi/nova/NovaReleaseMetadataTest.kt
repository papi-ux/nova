package com.papi.nova

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaReleaseMetadataTest {
    @Test
    fun standardLinuxBundleIncludesLazyPyrowaveAndIsRequiredForTags() {
        val root = repoRoot()
        val workflow = File(root, ".github/workflows/build.yml").readText()
        val standard = workflowJob(workflow, "deck-flatpak")
        val release = workflowJob(workflow, "build")
        assertTrue(standard.contains("manifest-path: clients/deck/packaging/flatpak/release-build.json"))
        val metadata = workflowStep(standard, "Prepare Linux release metadata")
        assertTrue(metadata.contains("shell: bash"))
        assertTrue(metadata.contains("tools/nova_flatpak_feed.py prepare-release"))
        assertTrue(metadata.contains("version_args+=(--version \"\$GITHUB_REF_NAME\")"))
        assertTrue(metadata.contains("clients/deck/packaging/flatpak/com.papi_ux.Nova.json"))
        assertTrue(standard.contains("ref: \${{ github.sha }}"))
        assertTrue(standard.contains("submodules: recursive"))
        assertTrue(standard.contains("if-no-files-found: error"))
        assertTrue(standard.contains("needs: [deck, deck-pyrowave]"))
        assertTrue(release.contains("needs: [verify, android-smoke, deck-pyrowave, deck-flatpak]"))
        assertTrue(release.contains("needs.deck-pyrowave.result == 'success'"))
        assertTrue(release.contains("needs.deck-flatpak.result == 'success'"))
        assertTrue(!workflow.contains("deck-flatpak-pyrowave"))
        assertTrue(!workflow.contains("Nova-Linux-PyroWave"))
        val checksums = workflowStep(release, "Checksum the Linux Alpha bundle")
        assertTrue(checksums.contains("sha256sum Nova-Linux-x86_64-alpha.flatpak > Nova-Linux-x86_64-alpha.flatpak.sha256"))
        val manifest = File(root, "clients/deck/packaging/flatpak/com.papi_ux.Nova.json").readText()
        assertTrue(manifest.contains("modules/pyrowave.json"))
        assertTrue(manifest.contains("-DNOVA_DECK_BUILD_PYROWAVE=ON"))
        assertTrue(!manifest.contains("NOVA_DECK_UPDATE_CHANNEL"))
        val cmake = File(root, "clients/deck/CMakeLists.txt").readText()
        assertTrue(cmake.contains("install(TARGETS nova-deck-pyrowave-probe"))
        assertTrue(!File(root, "clients/deck/packaging/flatpak/com.papi_ux.Nova.pyrowave.json").exists())
    }

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
    fun developmentAndReleaseNotesMetadataStayConsistent() {
        val root = repoRoot()
        val build = File(root, "app/build.gradle").readText()
        val changelog = File(root, "CHANGELOG.md").readText()
        val releaseScript = File(root, "bin/release.sh").readText()
        val releaseWorkflow = File(root, ".github/workflows/build.yml").readText()
        val storeNotes = File(
            root,
            "fastlane/metadata/android/en-US/changelogs/54.txt"
        )
        val storeNotesBody = if (storeNotes.isFile) storeNotes.readText().trimEnd() else ""

        assertTrue(build.contains("versionName \"1.5.0\""))
        // The Deck bundle reports the same version in its support report and its AppStream data.
        assertTrue(File(root, "clients/deck/CMakeLists.txt").readText().contains("project(NovaDeck VERSION 1.5.0 "))
        assertTrue(
            File(root, "clients/deck/packaging/flatpak/com.papi_ux.Nova.metainfo.xml").readText()
                .contains("<release version=\"1.4.13\" date=\"2026-09-24\"/>")
        )
        assertTrue(
            File(root, "clients/deck/packaging/flatpak/com.papi_ux.Nova.metainfo.xml").readText()
                .contains("<release version=\"1.5.0\" type=\"development\" date=\"2026-10-12\"/>"),
        )
        // Development builds advance to code 55. Keep 1.4.13's store notes under 54
        // until the next release's notes are prepared.
        assertTrue(build.contains("def novaVersionCode = 55"))
        assertTrue(build.contains("versionCode = novaVersionCode"))

        // A beta's code is derived from that pin, because beta.2 and beta.3 both shipped 54 when it
        // was hand-written and no package manager could tell them apart. The scheme scales the base
        // rather than adding to it: com.papi.nova.pre is one application id for every prerelease, so
        // the next version's first beta has to outrank this version's last rc, and base + N cannot
        // guarantee that when the base only climbs by one per release.
        assertTrue(build.contains("def novaPreReleaseVersionCode = novaVersionCode * 100 + novaPreReleaseChannelOrdinal"))
        assertTrue(build.contains("novaChannel == \"rc\" ? 50 + novaChannelNumber : novaChannelNumber"))
        // Past 49 the beta and rc bands collide and an rc stops outranking the betas.
        assertTrue(build.contains("novaChannelNumber < 1 || novaChannelNumber > 49"))
        // A build type cannot carry a versionCode, so it is applied to the variant outputs, and only
        // for preRelease: stable, dirty and benchmark keep the pin.
        assertTrue(build.contains("onVariants(selector().withBuildType(\"preRelease\"))"))
        assertTrue(build.contains("output.versionCode.set(novaPreReleaseVersionCode)"))
        assertTrue(changelog.contains("## 1.4.13 - 2026-09-24"))
        assertTrue(changelog.contains("Steam Input remains manual and read-only."))
        assertTrue(changelog.contains("Polaris owns encoder probing, fallback, and launch policy"))
        assertTrue(releaseWorkflow.contains("python3 scripts/extract_release_notes.py"))
        assertTrue(releaseWorkflow.contains("--notes-file \"\$release_notes\""))
        assertTrue(releaseWorkflow.contains("gh release edit \"\${GITHUB_REF_NAME}\""))
        assertTrue(releaseWorkflow.contains("published_notes="))
        assertTrue(!releaseWorkflow.contains("--generate-notes"))
        assertTrue(releaseScript.contains("-PnovaAbis=arm64-v8a,armeabi-v7a,x86_64"))
        // The names are chosen from the channel now rather than written out, because a beta
        // installs beside stable under com.papi.nova.pre and must not publish under stable's
        // filenames: anything keyed on those, including Nova's own documented Obtainium entry,
        // would fetch the beta while tracking the stable package and fail to read it at all.
        assertTrue(
            "the release script must publish a stable release under the stable asset names",
            releaseScript.contains("asset_prefix=Nova-Android"),
        )
        assertTrue(
            "the release script must publish a beta under its own asset names",
            releaseScript.contains("asset_prefix=Nova-Beta-Android"),
        )
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            assertTrue(
                "the release script must name the $abi asset it uploads",
                releaseScript.contains("\${asset_prefix}-$abi.apk"),
            )
        }
        assertTrue(storeNotes.isFile)
        assertTrue(
            "Google Play release notes must be at most 500 Unicode characters",
            storeNotesBody.codePointCount(0, storeNotesBody.length) <= 500,
        )
        assertTrue(storeNotesBody.startsWith("Nova 1.4.13"))
    }

    @Test
    fun releaseScriptTagsOnlyTheExactProtectedMasterHead() {
        val releaseScript = File(repoRoot(), "bin/release.sh").readText()
        val lines = releaseScript.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        assertTrue(releaseScript.contains("requested=\"\${1:-\$declared_version}\""))
        assertTrue(releaseScript.contains("version=\"\${requested%%-*}\""))
        assertTrue(releaseScript.contains("channel_suffix=\"\${requested#\"\$version\"}\""))
        assertConsecutive(lines, listOf(
            "if [[ \"\$version\" != \"\$declared_version\" ]]; then",
            "echo \"Requested release version \${version} does not match app version \${declared_version}.\" >&2",
            "exit 1",
            "fi",
        ))
        assertConsecutive(lines, listOf(
            "current_branch=\"\$(git symbolic-ref --quiet --short HEAD || true)\"",
            "if [[ \"\$current_branch\" != \"master\" ]]; then",
            "echo \"Release tagging requires the local master branch; found '\${current_branch:-detached HEAD}'.\" >&2",
            "exit 1",
            "fi",
        ))
        assertOrdered(lines, listOf(
            "git fetch --no-tags origin refs/heads/master:refs/remotes/origin/master",
            "local_head=\"\$(git rev-parse HEAD)\"",
            "remote_master=\"\$(git rev-parse refs/remotes/origin/master)\"",
            "if [[ \"\$local_head\" != \"\$remote_master\" ]]; then",
        ))
        assertTrue(lines.count { it == "require_exact_master_head" } == 2)
        assertOrdered(lines, listOf(
            "require_exact_master_head",
            "./gradlew -PnovaAbis=arm64-v8a,armeabi-v7a,x86_64 \"\${gradle_channel_flags[@]}\" " +
                "\"assembleNonRoot_game\${build_type}\"",
            "require_exact_master_head",
            "git tag -a \"\$tag\" -m \"Nova \${tag}\"",
            "if ! git push --atomic origin \\",
            "HEAD:refs/heads/master \\",
            "\"refs/tags/\${tag}:refs/tags/\${tag}\"",
        ))
        assertConsecutive(lines, listOf(
            "then",
            "git tag -d \"\$tag\" >/dev/null",
            "echo \"Atomic master/tag publication failed; removed local \${tag}.\" >&2",
            "exit 1",
            "fi",
        ))
        assertTrue(lines.none {
            it == "git push origin master" || it == "git push origin \"\$tag\""
        })
    }

    @Test
    fun aBetaTagBuildsTheSideBySideVariantAndPublishesAsAPrerelease() {
        val root = repoRoot()
        val workflow = File(root, ".github/workflows/build.yml").readText()
        val buildJob = workflowJob(workflow, "build")

        // The channel is resolved before anything is built, because the build type is part of it.
        val resolvePosition = buildJob.indexOf("      - name: Resolve release channel\n")
        val buildPosition = buildJob.indexOf("      - name: Build release APK\n")
        assertTrue("Missing the release channel resolution step", resolvePosition >= 0)
        assertTrue(
            "The channel must be resolved before the APK is built",
            buildPosition > resolvePosition,
        )

        val resolveLines = workflowRunLines(workflowStep(buildJob, "Resolve release channel"))
        assertNoHeredoc(resolveLines, "Release channel resolution")
        assertOrdered(resolveLines, listOf(
            "channel=stable",
            "build_type=Release",
            "variant=release",
            "if [[ \"\${GITHUB_REF}\" == refs/tags/* ]]; then",
            "if [[ ! \"\${GITHUB_REF_NAME}\" =~ ^v[0-9]+\\.[0-9]+\\.[0-9]+(-(beta|rc)\\.[0-9]+)?\$ ]]; then",
            "if [[ \"\${GITHUB_REF_NAME}\" == *-* ]]; then",
            "channel=prerelease",
            "build_type=PreRelease",
            "variant=preRelease",
        ))
        for (export in listOf(
            "echo \"NOVA_VERSION_SUFFIX=-\${GITHUB_REF_NAME#*-}\" >> \"\$GITHUB_ENV\"",
            "echo \"NOVA_RELEASE_CHANNEL=\${channel}\" >> \"\$GITHUB_ENV\"",
            "echo \"NOVA_BUILD_TYPE=\${build_type}\" >> \"\$GITHUB_ENV\"",
            "echo \"NOVA_APK_VARIANT=\${variant}\" >> \"\$GITHUB_ENV\"",
        )) {
            assertTrue("Release channel must export $export", resolveLines.contains(export))
        }

        // preRelease carries its own applicationId, which is the whole point: a beta installs beside
        // a stable Nova, so it can never block a stable update through its versionCode.
        val buildGradle = File(root, "app/build.gradle").readText()
        assertTrue(buildGradle.contains("applicationIdSuffix \".pre\""))
        assertTrue(buildGradle.contains("versionNameSuffix novaVersionSuffix"))
        assertTrue(buildGradle.contains(
            "providers.gradleProperty(\"novaVersionSuffix\").getOrElse(\"-beta\")"
        ))

        // Nothing downstream may name the stable variant, or a beta tag would ship stable APKs.
        val buildLines = workflowRunLines(workflowStep(buildJob, "Build release APK"))
        assertTrue(buildLines.contains(
            "./gradlew --no-build-cache \"\${gradle_flags[@]}\" \"assembleNonRoot_game\${NOVA_BUILD_TYPE}\""
        ))
        val signLines = workflowRunLines(workflowStep(buildJob, "Sign APKs"))
        val selectLines = workflowRunLines(workflowStep(buildJob, "Select APK artifacts"))
        for (lines in listOf(signLines, selectLines)) {
            assertTrue(lines.contains(
                "APK_DIR=\"app/build/outputs/apk/nonRoot_game/\${NOVA_APK_VARIANT}\""
            ))
            assertTrue(
                "The release APK directory must come from the resolved variant",
                lines.none { it.contains("apk/nonRoot_game/release") },
            )
        }
        assertTrue(signLines.contains(
            "unsigned_apks=(\"\${APK_DIR}\"/*\"\${NOVA_APK_VARIANT}\"-unsigned.apk)"
        ))
        assertTrue(selectLines.contains(
            "signed_apks=(\"\${APK_DIR}\"/*-\"\${NOVA_APK_VARIANT}\".apk)"
        ))
        assertTrue(selectLines.contains(
            "apk_files=(\"\${APK_DIR}\"/*\"\${NOVA_APK_VARIANT}\"-unsigned.apk)"
        ))

        // An asset name carries the channel as well as the ABI.
        //
        // It used to carry only the ABI, so that every published stable link kept resolving and
        // Obtainium needed no second configuration. The second half of that was wrong. A beta
        // installs beside stable under com.papi.nova.pre, and publishing it under stable's exact
        // filenames meant anything keyed on those names fetched a beta while tracking the stable
        // package: Nova's own documented Obtainium entry pins id com.papi.nova and the filter
        // Nova-Android-arm64-v8a.apk, and reported that it could not read the package at all.
        //
        // Stable keeps its names, so the first half still holds and every published link is
        // untouched. Only a beta is renamed, and it gets an Obtainium entry of its own.
        assertTrue(selectLines.contains("asset_name=\"\${NOVA_ASSET_PREFIX}-\${abi}.apk\""))
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
        val preflight = buildJob.indexOf("      - name: Verify prestaged Frame alpha\n")
        assertTrue(preflight > positions[0] && preflight < positions[1])
        assertTrue(buildJob.contains("fetch-depth: 0"))
        // The rule is that every lane pins the event source, not that there are
        // three lanes. Comparing the two counts keeps the invariant true when a
        // lane is added, and still fails the moment one checks out a floating
        // ref instead of the exact commit.
        val checkouts = workflow.lines().count { it == "      - uses: actions/checkout@v5" }
        assertTrue(checkouts > 0)
        assertTrue(workflow.lines().count { it == "          ref: \${{ github.sha }}" } == checkouts)
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
            "if [[ ! \"\${GITHUB_REF_NAME}\" =~ ^v[0-9]+\\.[0-9]+\\.[0-9]+(-(beta|rc)\\.[0-9]+)?\$ ]]; then",
            "checked_out_commit=\"\$(git rev-parse HEAD)\"",
            "git fetch --no-tags --force origin \\",
            "tag_commit=\"\$(git rev-parse \"refs/tags/\${GITHUB_REF_NAME}^{commit}\")\"",
            "if [ \"\$tag_commit\" != \"\$EXPECTED_SOURCE_COMMIT\" ]; then",
        ))
        assertConsecutive(tagGuardLines, listOf(
            "if [[ ! \"\${GITHUB_REF_NAME}\" =~ ^v[0-9]+\\.[0-9]+\\.[0-9]+(-(beta|rc)\\.[0-9]+)?\$ ]]; then",
            "echo \"Release tag must match vMAJOR.MINOR.PATCH with an optional -beta.N or -rc.N: " +
                "\${GITHUB_REF_NAME}\" >&2",
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
        // A rerun that finds the release already staged takes the edit path. A beta that lost its
        // channel there would publish as a stable release.
        assertTrue(stageLines.contains("channel=(--prerelease)"))
        assertTrue(stageLines.count { it == "\"\${channel[@]}\" \\" } == 2)
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
            "\"\${NOVA_ASSET_PREFIX}-arm64-v8a.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-arm64-v8a.apk.sha256\"",
            "\"\${NOVA_ASSET_PREFIX}-armeabi-v7a.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-armeabi-v7a.apk.sha256\"",
            "\"\${NOVA_ASSET_PREFIX}-x86_64.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-x86_64.apk.sha256\"",
            "Nova-Linux-x86_64-alpha.flatpak",
            "Nova-Linux-x86_64-alpha.flatpak.sha256",
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
            "echo \"Local release assets do not match the exact eight-file contract\" >&2",
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
            "\"\${NOVA_ASSET_PREFIX}-arm64-v8a.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-arm64-v8a.apk.sha256\"",
            "\"\${NOVA_ASSET_PREFIX}-armeabi-v7a.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-armeabi-v7a.apk.sha256\"",
            "\"\${NOVA_ASSET_PREFIX}-x86_64.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-x86_64.apk.sha256\"",
            "Nova-Linux-x86_64-alpha.flatpak",
            "Nova-Linux-x86_64-alpha.flatpak.sha256",
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
            "\"\${NOVA_ASSET_PREFIX}-arm64-v8a.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-arm64-v8a.apk.sha256\"",
            "\"\${NOVA_ASSET_PREFIX}-armeabi-v7a.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-armeabi-v7a.apk.sha256\"",
            "\"\${NOVA_ASSET_PREFIX}-x86_64.apk\"",
            "\"\${NOVA_ASSET_PREFIX}-x86_64.apk.sha256\"",
            "Nova-Linux-x86_64-alpha.flatpak",
            "Nova-Linux-x86_64-alpha.flatpak.sha256",
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
            "echo \"Release assets do not match the exact final eight/ten-file contract on \${GITHUB_REF_NAME}\" >&2",
            "printf 'expected: %s\\n' \"\${expected_assets[*]}\" >&2",
            "printf 'published: %s\\n' \"\${published_assets[*]}\" >&2",
            "exit 1",
            "fi",
        ))

        assertOrdered(verifyLines, listOf(
            "if [ \"\$GITHUB_REF_NAME\" = v1.5.0-beta.2 ]; then",
            "expected_assets+=(Nova-Linux-aarch64-frame-alpha.flatpak Nova-Linux-aarch64-frame-alpha.flatpak.sha256)",
            "if [ \"\${published_assets[*]}\" != \"\${expected_assets[*]}\" ]; then",
            "python3 \"\$RUNNER_TEMP/nova-release-readback.py\" final",
        ))

        val publish = workflowStep(buildJob, orderedSteps[4])
        assertTrue(publish.lines().any {
            it == "        if: startsWith(github.ref, 'refs/tags/v') && " +
                "steps.stage-release.outputs.publish_draft == 'true'"
        })
        assertTrue(workflowRunLines(publish) == listOf(
            "set -euo pipefail",
            "if [ \"\${NOVA_RELEASE_CHANNEL}\" = prerelease ]; then",
            "gh release edit \"\${GITHUB_REF_NAME}\" --verify-tag --draft=false --prerelease --latest=false",
            "else",
            "gh release edit \"\${GITHUB_REF_NAME}\" --verify-tag --draft=false",
            "fi",
        ))
    }

    @Test
    fun pinnedFrameReadbackRejectsMixedOrUnqualifiedReleaseInputs() {
        val workflow = File(repoRoot(), ".github/workflows/build.yml").readText()
        val preflight = workflowStep(workflowJob(workflow, "build"), "Verify prestaged Frame alpha")
        val start = "          cat > \"\$RUNNER_TEMP/nova-release-readback.py\" <<'PY'\n"
        val script = preflight.substringAfter(start).substringBefore("          PY\n")
            .lines().joinToString("\n") { it.removePrefix("          ") }
        assertTrue(script.startsWith("import hashlib\n"))
        val temp = java.nio.file.Files.createTempDirectory("nova-release-check-").toFile()
        try {
            val candidate = File(temp, "readback.py").apply { writeText(script) }
            val controls = File(temp, "controls.py").apply { writeText("""
import ast
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

code = Path(sys.argv.pop(1)).read_text()
assignment = next(n for n in ast.parse(code).body if isinstance(n, ast.Assign)
                  and any(isinstance(t, ast.Name) and t.id == "FRAME" for t in n.targets))
lines = code.splitlines(keepends=True)
real_git = shutil.which("git")
name = "Nova-Linux-aarch64-frame-alpha.flatpak"
managed = ["Nova-Beta-Android-" + abi + ".apk" for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")]
managed += ["Nova-Linux-x86_64-alpha.flatpak"]

class ReleaseReadback(unittest.TestCase):
    def exercise(self, case, phase, succeeds):
        with tempfile.TemporaryDirectory(prefix="nova-release-contract-") as tmp:
            root = Path(tmp)
            for d in ("bin", "remote", "local", "runner", "scripts", "clients/deck/qml", "clients/deck/tests", ".github/workflows"):
                (root / d).mkdir(parents=True, exist_ok=True)
            env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                       GIT_ALLOW_PROTOCOL="", GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.com",
                       GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.com",
                       REAL_GIT=real_git, GH_FIXTURE=tmp)
            def git(*args):
                return subprocess.check_output([real_git, *args], cwd=root, env=env, text=True).strip()
            git("init", "-q")
            (root / "scripts/extract_release_notes.py").write_text('print("Curated notes")\n')
            (root / "clients/deck/qml/NovaHud.qml").write_text("qualified QML\n")
            (root / "clients/deck/tests/deck_library_navigation_test.py").write_text("qualified test fixture\n")
            (root / "CHANGELOG.md").write_text("qualified notes\n")
            (root / "README.md").write_text("qualified README\n")
            (root / ".github/workflows/build.yml").write_text("old release-only metadata\n")
            git("add", "."); git("commit", "-qm", "qualified source")
            source, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
            (root / ".github/workflows/build.yml").write_text("new release-only metadata\n")
            git("add", "."); git("commit", "-qm", "release-only successor")
            release = git("rev-parse", "HEAD")
            tag = "v1.5.0-beta.1" if case.startswith("ordinary") else "v1.5.0-beta.2"
            git("tag", tag)
            (root / "body").write_text("Curated notes\n")
            (root / "runner/nova-release-notes.md").write_text("Curated notes\n")
            for leaf in managed + [name]:
                payload = ("fixture bytes for " + leaf).encode()
                checksum = (hashlib.sha256(payload).hexdigest() + "  " + leaf + "\n").encode()
                for dest in ("remote", "local"):
                    (root / dest / leaf).write_bytes(payload)
                    (root / dest / (leaf + ".sha256")).write_bytes(checksum)
            payload = (root / "remote" / name).read_bytes()
            pins = dict(source=source, tree=tree, app_ref="app/com.papi_ux.Nova/aarch64/frame-alpha",
                        app_commit="1" * 64, runtime_ref="org.kde.Platform/aarch64/6.10", runtime_commit="2" * 64,
                        size=len(payload), sha256=hashlib.sha256(payload).hexdigest(),
                        sidecar=(root / "remote" / (name + ".sha256")).read_text())
            if case.startswith("ordinary") or case == "missing":
                for leaf in (name, name + ".sha256"): (root / "remote" / leaf).unlink()
            if case == "draft-pair":
                for leaf in managed:
                    for suffix in ("", ".sha256"): (root / "remote" / (leaf + suffix)).unlink()
            if case == "half": (root / "remote" / (name + ".sha256")).unlink()
            if case in ("extra", "ordinary-extra"): (root / "remote/unknown.bin").write_bytes(b"unknown")
            if case == "hash": (root / "remote" / name).write_bytes(b"wrong")
            if case == "managed-hash": (root / "remote" / managed[0]).write_bytes(b"wrong")
            if case == "unsafe-checksum": (root / "remote" / (name + ".sha256")).write_text("a" * 64 + "  ../../escape\n")
            if case == "held": pins["source"] = "PENDING"
            if case == "tree": pins["tree"] = "0" * 40
            if case == "runtime-qml":
                (root / "clients/deck/qml/NovaHud.qml").write_text("unqualified QML\n")
                git("add", "clients/deck/qml/NovaHud.qml"); git("commit", "-qm", "runtime drift")
                release = git("rev-parse", "HEAD"); git("tag", "-f", tag)
            if case == "changelog-notes":
                (root / "CHANGELOG.md").write_text("corrected notes\n")
                git("add", "CHANGELOG.md"); git("commit", "-qm", "release notes correction")
                release = git("rev-parse", "HEAD"); git("tag", "-f", tag)
            if case == "allowed-test-change":
                (root / "clients/deck/tests/deck_library_navigation_test.py").write_text("diagnostic-only test fixture\n")
                git("add", "clients/deck/tests/deck_library_navigation_test.py"); git("commit", "-qm", "test diagnostic correction")
                release = git("rev-parse", "HEAD"); git("tag", "-f", tag)
            if case in ("readme-only", "readme-near-name"):
                path = "README.md" if case == "readme-only" else "README.md.extra"
                (root / path).write_text("corrected README\n")
                git("add", path); git("commit", "-qm", "README correction")
                release = git("rev-parse", "HEAD"); git("tag", "-f", tag)
            media_cases = {
                "readme-image": "docs/screenshots/beta2/nova-control-detail-aurora.webp",
                "readme-director": "docs/screenshots/beta2/nova-control-detail-director.webp",
                "readme-play-setup": "docs/screenshots/beta2/nova-play-setup-aurora.webp",
                "readme-provenance": "docs/screenshots/beta2/provenance.json",
                "readme-image-near-name": "docs/screenshots/beta2/nova-control-detail-aurora.webp.extra",
                "readme-other-image": "docs/screenshots/beta2/other.webp",
            }
            if case in media_cases:
                path = media_cases[case]
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_bytes(b"documentation media fixture\n")
                git("add", path); git("commit", "-qm", "documentation image successor")
                release = git("rev-parse", "HEAD"); git("tag", "-f", tag)
            android_cases = {
                "android-activity": "app/src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt",
                "android-activity-near-name": "app/src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt.orig",
                "android-other-source": "app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt",
            }
            if case in android_cases:
                path = android_cases[case]
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_text("Android-only source fixture\n")
                git("add", path); git("commit", "-qm", "Android-only successor")
                release = git("rev-parse", "HEAD"); git("tag", "-f", tag)
            if case == "tag": git("tag", "-f", tag, source)
            if case == "notes": (root / "body").write_text("different notes\n")
            if case == "notes-source": (root / "runner/nova-release-notes.md").write_text("stale notes\n")
            candidate = "".join(lines[:assignment.lineno - 1]) + "FRAME = " + repr(pins) + "\n" + "".join(lines[assignment.end_lineno:])
            (root / "readback.py").write_text(candidate)
            gh = 'import json, os\nfrom pathlib import Path\nimport shutil, sys\nr = Path(os.environ["GH_FIXTURE"])\na = sys.argv[1:]\nwith (r / "calls").open("a") as f: f.write(json.dumps(a) + "\\n")\nif a[:2] == ["release", "view"] and a[a.index("--json") + 1] == "assets":\n    print(json.dumps({"assets":[{"name":p.name} for p in (r / "remote").iterdir()]}))\nelif a[:2] == ["release", "view"] and a[a.index("--json") + 1] == "body":\n    print((r / "body").read_text(), end="")\nelif a[:2] == ["release", "download"]:\n    shutil.copyfile(r / "remote" / a[a.index("--pattern") + 1], Path(a[a.index("--dir") + 1]) / a[a.index("--pattern") + 1])\nelse:\n    raise SystemExit("Unexpected/mutating gh command")\n'
            wrapper = 'import os, sys\nif sys.argv[1] == "fetch": sys.exit(0) # no transport in this pure fixture\nos.execv(os.environ["REAL_GIT"], ["git"] + sys.argv[1:])\n'
            for leaf, body in (("gh", gh), ("git", wrapper)):
                p = root / "bin" / leaf; p.write_text("#!" + sys.executable + "\n" + body); p.chmod(0o700)
            env.update(PATH=str(root / "bin") + os.pathsep + env["PATH"], GITHUB_REF_NAME=tag,
                       EXPECTED_SOURCE_COMMIT=release, NOVA_ASSET_PREFIX="Nova-Beta-Android",
                       RUNNER_TEMP=str(root / "runner"), APK_ASSET_DIR=str(root / "local"))
            before = {p.name:p.read_bytes() for p in (root / "remote").iterdir()}
            result = subprocess.run([sys.executable, str(root / "readback.py"), phase], cwd=root, env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            self.assertEqual(succeeds, result.returncode == 0, case + ": " + result.stdout)
            self.assertEqual(before, {p.name:p.read_bytes() for p in (root / "remote").iterdir()})
            calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()] if (root / "calls").exists() else []
            self.assertTrue(all(a[:2] in (["release", "view"], ["release", "download"]) for a in calls))
            if case in ("held", "tree", "runtime-qml", "readme-near-name", "readme-image-near-name", "readme-other-image",
                        "android-activity-near-name", "android-other-source"): self.assertEqual([], calls)
            print(case + ": expected " + ("PASS" if succeeds else "REFUSE"), flush=True)

    def test_changed_release_boundaries(self):
        for case, phase, succeeds in (
                ("ordinary-eight", "final", True), ("draft-pair", "preflight", True),
                ("final-ten", "final", True), ("rerun-ten", "preflight", True),
                ("missing", "preflight", False), ("half", "preflight", False),
                ("hash", "preflight", False), ("unsafe-checksum", "preflight", False),
                ("extra", "preflight", False), ("ordinary-extra", "final", False),
                ("runtime-qml", "preflight", False), ("changelog-notes", "preflight", True),
                ("allowed-test-change", "preflight", True),
                ("readme-only", "preflight", True), ("readme-near-name", "preflight", False),
                ("readme-image", "preflight", True), ("readme-director", "preflight", True),
                ("readme-play-setup", "preflight", True), ("readme-provenance", "preflight", True),
                ("readme-image-near-name", "preflight", False), ("readme-other-image", "preflight", False),
                ("android-activity", "preflight", True), ("android-activity-near-name", "preflight", False),
                ("android-other-source", "preflight", False),
                ("tree", "preflight", False),
                ("tag", "final", False), ("notes", "final", False), ("notes-source", "final", False),
                ("held", "preflight", False), ("managed-hash", "final", False)):
            with self.subTest(case=case): self.exercise(case, phase, succeeds)

unittest.main(failfast=True)
""".trimIndent()) }
            val result = ProcessBuilder("python3", controls.path, candidate.path)
                .redirectErrorStream(true).start()
            val output = result.inputStream.bufferedReader().readText()
            assertTrue(output, result.waitFor() == 0)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun publicSurfaceAllowsOnlyExplicitSyntheticRedactionPaths() {
        val temp = java.nio.file.Files.createTempDirectory("nova-surface-check-").toFile()
        try {
            val controls = File(temp, "controls.py").apply { writeText("""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

checker = Path(sys.argv[1]).read_bytes()
home = "/" + "home/"
known = "app/src/test/java/com/papi/nova/diagnostics/NovaMomentLogTest.kt"
for case, extra, succeeds in (
        ("synthetic", {}, True),
        ("other-home-inside", {known:home + "nonallowed/Documents"}, False),
        ("synthetic-home-outside", {"other.txt":home + "redaction-fixture/Documents"}, False),
        ("tracked-secret", {"signing.properties":"fixture only"}, False),
        ("other-marker-inside", {known:home + "redaction-fixture/Documents project " + "memory anchor"}, False)):
    with tempfile.TemporaryDirectory(prefix="nova-surface-") as tmp:
        root = Path(tmp)
        files = {
            "scripts/check-public-surface.sh":checker,
            known:(home + "redaction-fixture/Documents and /" + "Users/redaction-fixture/Library").encode(),
            "clients/deck/tests/deck_moment_report_test.cpp":('"' + home + 'private"').encode(),
            "clients/deck/tests/deck_working_baseline_test.cpp":('"' + home + 'private"').encode(),
        }
        files.update({name:body.encode() for name, body in extra.items()})
        for name, body in files.items():
            p = root / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(body)
        env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_ALLOW_PROTOCOL="")
        subprocess.run(["git", "init", "-q"], cwd=root, env=env, check=True)
        subprocess.run(["git", "add", "."], cwd=root, env=env, check=True)
        r = subprocess.run(["/bin/bash", "scripts/check-public-surface.sh"], cwd=root, env=env,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert succeeds == (r.returncode == 0), case + ": " + r.stdout
        print(case + ": expected " + ("PASS" if succeeds else "REFUSE"))
""".trimIndent()) }
            val result = ProcessBuilder("python3", controls.path,
                File(repoRoot(), "scripts/check-public-surface.sh").path)
                .redirectErrorStream(true).start()
            val output = result.inputStream.bufferedReader().readText()
            assertTrue(output, result.waitFor() == 0)
        } finally {
            temp.deleteRecursively()
        }
    }
}
