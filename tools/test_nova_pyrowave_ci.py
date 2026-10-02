import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from tools import build_nova_pyrowave_prefix as prefix
from tools import run_nova_pyrowave_ci as gate

MODULE = Path(__file__).resolve().parents[1] / 'clients/deck/packaging/flatpak/modules/pyrowave.json'

class NovaPyroWaveCiTest(unittest.TestCase):
    def inventory(self):
        tests = [{'name': n, 'properties': []} for n in sorted(gate.MANDATORY_TESTS)]
        tests.append({'name': 'hardware_fixture', 'properties': [{'name': 'SKIP_RETURN_CODE', 'value': 77}]})
        return {'tests': tests}

    def result(self, inventory, skipped=(), failed=(), omitted=()):
        suite = ET.Element('testsuite')
        for t in inventory['tests']:
            if t['name'] in omitted:
                continue
            c = ET.SubElement(suite, 'testcase', name=t['name'])
            if t['name'] in skipped: ET.SubElement(c, 'skipped')
            if t['name'] in failed: ET.SubElement(c, 'failure')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'tests.xml'
            ET.ElementTree(suite).write(path)
            return gate.validate_results(path, inventory)

    def test_missing_real_helper_registration_blocks_lane(self):
        inventory = self.inventory()
        inventory['tests'] = [t for t in inventory['tests'] if t['name'] != 'nova_deck_pyrowave_real_probe_test']
        with self.assertRaises(ValueError): gate.validate_inventory(inventory)

    def test_missing_refusal_registration_blocks_lane(self):
        inventory = self.inventory()
        inventory['tests'] = [t for t in inventory['tests'] if t['name'] != 'nova_pyrowave_refusal_test']
        with self.assertRaises(ValueError): gate.validate_inventory(inventory)

    def test_omitted_registered_execution_blocks_lane(self):
        with self.assertRaises(ValueError): self.result(self.inventory(), omitted={'hardware_fixture'})

    def test_real_helper_skip_blocks_even_when_return_code_is_declared(self):
        inventory = self.inventory()
        for t in inventory['tests']:
            if t['name'] == 'nova_deck_pyrowave_real_probe_test':
                t['properties'] = [{'name': 'SKIP_RETURN_CODE', 'value': 77}]
        with self.assertRaises(ValueError): self.result(inventory, skipped={'nova_deck_pyrowave_real_probe_test'})

    def test_any_actual_failure_blocks_lane(self):
        with self.assertRaises(ValueError): self.result(self.inventory(), failed={'hardware_fixture'})

    def test_only_explicit_hardware_unavailability_is_recorded(self):
        result = self.result(self.inventory(), skipped={'hardware_fixture'})
        self.assertEqual(result['hardware_unavailable'], ['hardware_fixture'])
        self.assertEqual(result['executed'], len(gate.MANDATORY_TESTS))
        self.assertEqual(result['failures'], 0)

    def test_unqualified_skip_blocks_lane(self):
        inventory = self.inventory()
        inventory['tests'][-1]['properties'] = []
        with self.assertRaises(ValueError): self.result(inventory, skipped={'hardware_fixture'})

    def elf(self, path, machine=62, elf_class=2, elf_type=3):
        header = bytearray(64)
        header[:7] = b'\x7fELF\x02\x01\x01'
        header[4] = elf_class
        header[16:18] = elf_type.to_bytes(2, 'little')
        header[18:20] = machine.to_bytes(2, 'little')
        path.write_bytes(header)
        return path

    def icd(self, path, library):
        path.write_text(json.dumps({'ICD': {'library_path': str(library)}}))
        return path

    def test_native_icd_selection_ignores_foreign_multilib_driver(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library = self.elf(root / 'native.so')
            manifest = self.icd(root / 'lvp_icd.x86_64.json', library)
            self.icd(root / 'lvp_icd.i686.json', self.elf(root / 'foreign.so', elf_class=1, machine=3))
            self.assertEqual(gate.software_icd(root, 'x86_64'), manifest)
            with self.assertRaises(ValueError): gate.software_icd(root, 'aarch64')

    def test_generic_icd_resolves_exact_native_loader_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            native = self.elf(root / 'native.so')
            foreign = self.elf(root / 'foreign.so', elf_class=1, machine=3)
            manifest = self.icd(root / 'lvp_icd.json', 'libvulkan_lvp.so')
            cache = f'  libvulkan_lvp.so (libc6) => {foreign}\n  libvulkan_lvp.so (libc6,x86-64) => {native}\n'
            with patch('subprocess.check_output', return_value=cache):
                self.assertEqual(gate.software_icd(root, 'x86_64'), manifest)
                self.assertEqual(gate.icd_library(manifest, 'x86_64'), native.resolve())

    def test_generic_icd_rejects_foreign_32bit_or_nonshared_library(self):
        for machine, elf_class, elf_type in [(183, 2, 3), (62, 1, 3), (62, 2, 2)]:
            with self.subTest(machine=machine, elf_class=elf_class, elf_type=elf_type), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                library = self.elf(root / 'wrong.so', machine, elf_class, elf_type)
                self.icd(root / 'lvp_icd.json', library)
                with self.assertRaises(ValueError): gate.software_icd(root, 'x86_64')

    def test_wrong_specific_icd_is_not_bypassed_by_valid_generic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.icd(root / 'lvp_icd.x86_64.json', self.elf(root / 'wrong.so', machine=183))
            self.icd(root / 'lvp_icd.json', self.elf(root / 'native.so'))
            with self.assertRaises(ValueError): gate.software_icd(root, 'x86_64')

    def test_shipped_four_pins_and_profile_are_accepted(self):
        module = prefix.read_module(MODULE)
        self.assertEqual(len(module['sources']), 4)

    def test_implicit_submodule_or_unpinned_dependency_blocks_prefix(self):
        for change in ['pin', 'implicit', 'destination', 'profile']:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                module = json.loads(MODULE.read_text())
                if change == 'pin': module['sources'][0]['commit'] = 'main'
                if change == 'implicit': module['sources'][1]['disable-submodules'] = False
                if change == 'destination': module['sources'][2]['dest'] = '../foreign'
                if change == 'profile': module['config-opts'][0] = '-DCMAKE_BUILD_TYPE=Debug'
                path = Path(directory) / 'module.json'
                path.write_text(json.dumps(module))
                with self.assertRaises(ValueError): prefix.read_module(path)

if __name__ == '__main__': unittest.main()
