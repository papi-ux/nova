import json
from pathlib import Path
import tempfile
import unittest
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
