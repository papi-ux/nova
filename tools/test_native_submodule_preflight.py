import pathlib
import re
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
APP_BUILD = REPO_ROOT / "app" / "build.gradle"
README = REPO_ROOT / "README.md"
TECHNICAL_OVERVIEW = REPO_ROOT / "docs" / "technical-overview.md"


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
        build_gradle = APP_BUILD.read_text(encoding="utf-8")

        self.assertRegex(
            build_gradle,
            re.compile(
                r"tasks\.configureEach\s*\{.*externalNativeBuild.*dependsOn\(verifyMoonlightCommonCSubmodule\)",
                re.DOTALL,
            ),
        )

    def test_docs_describe_clone_recovery_ndk_and_prebuilt_scope(self):
        readme = README.read_text(encoding="utf-8")
        overview = TECHNICAL_OVERVIEW.read_text(encoding="utf-8")
        combined_docs = f"{readme}\n{overview}"

        self.assertIn("git clone --recursive https://github.com/papi-ux/nova.git", combined_docs)
        self.assertIn("git submodule update --init --recursive", combined_docs)
        self.assertIn("27.0.12077973", combined_docs)
        self.assertIn("moonlight-common-c", combined_docs)
        self.assertIn("prebuilt native artifacts", combined_docs)
        self.assertIn("separate release-engineering decision", combined_docs)


if __name__ == "__main__":
    unittest.main()
