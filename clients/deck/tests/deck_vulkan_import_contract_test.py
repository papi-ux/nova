"""Unavailable hardware and software reference mode cannot produce acceptance."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile

with tempfile.TemporaryDirectory(prefix="nova-import-contract-") as directory:
    missing = str(Path(directory) / "missing-render-node")
    cases = [
        (["--device", missing], 77, "unavailable", "vaapi-device"),
        (["--require-hardware", "--device", missing], 1, "unavailable", "vaapi-device"),
        (["--reference", "--require-hardware"], 1, "failed", "arguments"),
        (["--reference", "--device", missing], 1, "failed", "arguments"),
    ]
    for args, expected_exit, status, stage in cases:
        run = subprocess.run([sys.argv[1], *args], capture_output=True, text=True, timeout=10)
        assert run.returncode == expected_exit, (args, run.returncode, run.stderr)
        report = json.loads(run.stdout)
        assert report["schema"] == 1 and report["status"] == status and report["stage"] == stage, report
        for name in ("hardware_import_verified", "hdr_display_verified", "nv12_sdr", "p010_pq", "p010_sdr"):
            assert report[name] is False, (name, report)
        assert missing not in run.stdout, "receipt leaked the device path"
print("Unavailable/required-hardware exits and conflicting software mode preserve false acceptance fields")
