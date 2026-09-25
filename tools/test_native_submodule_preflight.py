import pathlib
import re
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
APP_BUILD = REPO_ROOT / "app" / "build.gradle"
README = REPO_ROOT / "README.md"
TECHNICAL_OVERVIEW = REPO_ROOT / "docs" / "technical-overview.md"
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "build.yml"


class NativeSubmodulePreflightTest(unittest.TestCase):
    def test_gradle_declares_actionable_moonlight_common_c_preflight(self):
        build_gradle = APP_BUILD.read_text(encoding="utf-8")

        self.assertIn("verifyMoonlightCommonCSubmodule", build_gradle)
        self.assertIn("moonlight-common-c", build_gradle)
        self.assertIn("git submodule update --init --recursive", build_gradle)
        self.assertIn("src/main/jni/moonlight-core/moonlight-common-c", build_gradle)

        required_paths = [
            "src/Connection.c",
            "enet/include/enet/enet.h",
            "reedsolomon/rs.c",
        ]
        for required_path in required_paths:
            self.assertIn(required_path, build_gradle)

    def test_preflight_runs_before_native_build_tasks(self):
        """The guard has to attach to the task names this project actually produces.

        The previous version of this test asserted only that the string "externalNativeBuild"
        appeared next to the dependsOn, which is what the build script said and not what the build
        did. AGP names these tasks after the native build system in use, and this project configures
        ndkBuild, so the real names are configureNdkBuildRelease and buildNdkBuildRelease and contain
        no such word. The guard therefore attached to nothing, never ran once, and CI built release
        APKs from an unpatched submodule while this test passed.

        A source text test cannot ask Gradle what the task graph looks like. What it can do is tie
        the condition to the build system the project declares, so switching to CMake fails here
        rather than silently detaching the guard again.
        """
        build_gradle = APP_BUILD.read_text(encoding="utf-8")

        self.assertRegex(
            build_gradle,
            re.compile(r"externalNativeBuild\s*\{\s*ndkBuild\s*\{", re.DOTALL),
            "this project builds native code with ndkBuild; the guard condition below assumes it",
        )

        condition = re.search(
            r"tasks\.configureEach\s*\{[^}]*?if\s*\((.*?)\)\s*\{[^}]*?"
            r"dependsOn\(verifyMoonlightCommonCSubmodule\)",
            build_gradle,
            re.DOTALL,
        )
        self.assertIsNotNone(condition, "the preflight is not attached to any task")
        self.assertIn(
            "NdkBuild",
            condition.group(1),
            "the preflight must match the ndkBuild task names AGP actually creates, "
            "not only the word externalNativeBuild, which no task here is called",
        )

    def test_ci_applies_the_protocol_patches_before_building_native_code(self):
        """A checkout is unpatched, so CI has to patch it or build a library that cannot negotiate.

        The submodule is pinned at its upstream commit and Nova's protocol changes live beside it as
        a patch series. Nothing applies them on a fresh checkout, so without this step the library
        does not know the format: a client that asks for PyroWave gets a session that negotiates
        something else while Nova has already built a PyroWave renderer.
        """
        workflow = WORKFLOW.read_text(encoding="utf-8")

        apply_at = workflow.find("am ../patches/*.patch")
        self.assertNotEqual(apply_at, -1, "CI never applies the protocol patch series")

        # Asserted rather than trusted: a partial apply leaves a library that builds and cannot
        # negotiate, which is the whole failure the step exists to prevent.
        verify_at = workflow.find("grep -q PYROWAVE_PROFILE_TOKEN", apply_at)
        self.assertNotEqual(verify_at, -1, "CI applies the patches without checking they took")

        build_at = workflow.find('"assembleNonRoot_game${NOVA_BUILD_TYPE}"', verify_at)
        self.assertNotEqual(build_at, -1, "the patches are applied after the APK is built")

    def test_docs_describe_clone_recovery_ndk_and_prebuilt_scope(self):
        readme = README.read_text(encoding="utf-8")
        overview = TECHNICAL_OVERVIEW.read_text(encoding="utf-8")
        combined_docs = f"{readme}\n{overview}"

        self.assertIn("git clone --recursive https://github.com/papi-ux/nova.git", combined_docs)
        self.assertIn("git submodule update --init --recursive", combined_docs)
        self.assertIn("27.0.12077973", combined_docs)
        self.assertIn("moonlight-common-c", combined_docs)
        self.assertIn("prebuilt native artifacts", combined_docs.lower())
        self.assertIn("separate release-engineering decision", combined_docs)


if __name__ == "__main__":
    unittest.main()
