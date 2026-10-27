"""Focused Nova driver tests; product schemas remain the pinned upstream bytes."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
DRIVER = 'tools/generate-nova-polaris-contract.py'
OUTPUTS = (
    'shared/polaris/model/src/commonMain/kotlin/com/papi/nova/shared/polaris/contract/PolarisV1Contract.kt',
    'clients/deck/src/generated/polaris_v1_contract.h',
)


class NovaPolarisContractGeneratorTests(unittest.TestCase):
    def fixture(self, root):
        for name in (DRIVER, 'tools/generate-polaris-contract.py',
                     'shared/polaris/contract/polaris-v1-contract.json',
                     'shared/polaris/contract/PROVENANCE.json'):
            dest = root / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, dest)

    def run_driver(self, root, *args):
        return subprocess.run([sys.executable, '-B', str(root / DRIVER), '--root', str(root), *args],
                              capture_output=True, text=True)

    def snapshot(self, root):
        return {name: ((root / name).read_bytes(), (root / name).stat().st_mtime_ns)
                for name in OUTPUTS}

    def test_deterministic_write_and_read_only_clean_check(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            first = self.run_driver(root)
            self.assertEqual(0, first.returncode, first.stderr)
            before = self.snapshot(root)
            self.assertEqual(0, self.run_driver(root, '--check').returncode)
            self.assertEqual(before, self.snapshot(root), '--check mutated committed output')
            self.assertEqual(0, self.run_driver(root).returncode)
            self.assertEqual({k: v[0] for k, v in before.items()},
                             {k: v[0] for k, v in self.snapshot(root).items()})

    def test_check_refuses_stale_output_without_rewriting_it(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            self.assertEqual(0, self.run_driver(root).returncode)
            path = root / OUTPUTS[0]
            path.write_bytes(path.read_bytes() + b'// stale\n')
            before = self.snapshot(root)
            result = self.run_driver(root, '--check')
            self.assertEqual(1, result.returncode)
            self.assertIn('stale generated output:', result.stderr)
            self.assertEqual(before, self.snapshot(root))

    def test_provenance_refuses_changed_canonical_bytes_before_writing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            schema = root / 'shared/polaris/contract/polaris-v1-contract.json'
            schema.write_bytes(schema.read_bytes() + b'\n')
            result = self.run_driver(root)
            self.assertEqual(1, result.returncode)
            self.assertIn('provenance mismatch:', result.stderr)
            self.assertTrue(all(not (root / name).exists() for name in OUTPUTS))


if __name__ == '__main__':
    unittest.main()
