"""Opt-in, temporary-repository smoke: never installs or launches an app."""
import os
from contextlib import ExitStack
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools import nova_flatpak_feed as feed


@unittest.skipUnless(os.environ.get("NOVA_FLATPAK_SIGNED_TEST") == "1", "explicit temporary signing smoke only")
class SigningSmoke(unittest.TestCase):
    def test_signed_summary_and_commit_then_untrusted_rejection(self):
        for command in ("flatpak", "ostree", "gpg"):
            self.assertIsNotNone(shutil.which(command), command)
        with ExitStack() as cleanup:
            directory = cleanup.enter_context(tempfile.TemporaryDirectory(prefix="nova-feed-signing-"))
            root = Path(directory)
            key_home = root / "keys"; key_home.mkdir(mode=0o700)
            env = os.environ | {"GNUPGHOME": str(key_home)}
            cleanup.callback(subprocess.run, ["gpgconf", "--kill", "gpg-agent"], env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            def run(*args, ok=True):
                result = subprocess.run(list(map(str, args)), env=env, text=True, capture_output=True, timeout=60)
                if ok and result.returncode:
                    self.fail(f"{args[0]} failed: {result.stderr[-2000:]}")
                return result

            run("gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "", "--quick-generate-key",
                "Nova Feed Test <test@example.invalid>", "ed25519", "sign", "1d")
            key_id = next(row.split(":")[9] for row in run("gpg", "--with-colons", "--list-secret-keys").stdout.splitlines() if row.startswith("fpr:"))
            public = subprocess.check_output(["gpg", "--batch", "--export", key_id], env=env)
            key = root / "public.gpg"; key.write_bytes(public)
            app = root / "app"; (app / "files/bin").mkdir(parents=True); (app / "export").mkdir()
            (app / "metadata").write_text("[Application]\nname=com.papi_ux.Nova\nruntime=org.kde.Platform/x86_64/6.10\nsdk=org.kde.Sdk/x86_64/6.10\ncommand=nova-deck\n")
            executable = app / "files/bin/nova-deck"
            executable.write_text("#!/bin/sh\nexit 0\n"); executable.chmod(0o755)
            unsigned = root / "unsigned"
            run("flatpak", "build-export", "--arch=x86_64", unsigned, app, "beta")
            ref = f"app/{feed.APP}/x86_64/beta"
            repo = root / "site/repo"
            repo.parent.mkdir()
            run("ostree", f"--repo={repo}", "init", "--mode=archive-z2")
            run("ostree", f"--repo={repo}", "pull-local", unsigned, ref)
            revision = run("ostree", f"--repo={repo}", "rev-parse", ref).stdout.strip()
            run("ostree", f"--repo={repo}", "gpg-sign", revision, key_id)
            run("flatpak", "build-update-repo", f"--gpg-sign={key_id}", f"--gpg-homedir={key_home}", repo)
            feed.write_site(repo, root / "site", root / "previous", "beta", "v1.4.13-beta.1", "https://example.org/nova", public)
            run("flatpak", "build-bundle", "--repo-url=https://example.org/nova/repo", f"--gpg-keys={key}",
                repo, root / "Nova.flatpak", feed.APP, "beta")
            self.assertTrue((root / "Nova.flatpak").is_file())

            def verifier(name, import_key):
                verify = root / name
                run("ostree", f"--repo={verify}", "init", "--mode=archive-z2")
                options = [f"--gpg-import={key}"] if import_key else []
                run("ostree", f"--repo={verify}", "remote", "add", *options,
                    "--set=gpg-verify=true", "--set=gpg-verify-summary=true", "candidate", repo.as_uri())
                return run("ostree", f"--repo={verify}", "pull", "--untrusted", "candidate", ref, ok=import_key)

            self.assertEqual(verifier("verified", True).returncode, 0)
            self.assertNotEqual(verifier("untrusted", False).returncode, 0)
            # Retain the signed summary but remove the commit signature. A
            # verifier must reject this independently of the summary check.
            detached = repo / "objects" / revision[:2] / (revision[2:] + ".commitmeta")
            self.assertTrue(detached.is_file(), "fixture has no detached commit signature")
            detached.unlink()
            rejected = root / "unsigned-commit"
            run("ostree", f"--repo={rejected}", "init", "--mode=archive-z2")
            run("ostree", f"--repo={rejected}", "remote", "add", f"--gpg-import={key}",
                "--set=gpg-verify=true", "--set=gpg-verify-summary=true", "candidate", repo.as_uri())
            result = run("ostree", f"--repo={rejected}", "pull", "--untrusted", "candidate", ref, ok=False)
            self.assertNotEqual(result.returncode, 0, "unsigned commit passed a signed summary")
            self.assertRegex(result.stderr.lower(), "gpg|signature")


if __name__ == "__main__":
    unittest.main()
