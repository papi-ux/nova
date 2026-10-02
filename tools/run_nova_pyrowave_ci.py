#!/usr/bin/env python3
"""Required native Linux Release codec-on build and complete registered CTest lane."""
import argparse, hashlib, json, os, platform, shlex, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ET

MANDATORY_TESTS = {
    'nova_pyrowave_protocol_test', 'nova_pyrowave_parser_test', 'nova_pyrowave_refusal_test',
    'nova_deck_pyrowave_probe_test', 'nova_deck_pyrowave_real_probe_test',
    'nova_deck_play_settings_test', 'nova_deck_native_session_test', 'nova_deck_live_network_test',
    'nova_deck_game_tools_qml_test', 'nova_deck_native_preview_qml_test',
    'nova_deck_display_settings_qml_test', 'nova_deck_play_setup_route_test',
    'nova_deck_game_tools_route_test', 'nova_deck_test_assertions_test',
}
ASSERTION_TARGETS = {
    'nova_deck_test_assertions_test', 'nova_deck_backend_interfaces_test',
    'nova_deck_gamestream_launch_test', 'nova_deck_gamestream_session_builder_test',
    'nova_deck_layout_test', 'nova_deck_live_read_only_state_test',
    'nova_deck_moonlight_handoff_preflight_test', 'nova_deck_moonlight_identity_test',
    'nova_deck_moonlight_launcher_test', 'nova_deck_polaris_client_test',
    'nova_deck_steam_shortcuts_test', 'nova_deck_stream_core_test',
}

def validate_inventory(inventory):
    names = [test['name'] for test in inventory['tests']]
    if len(names) != len(set(names)) or not MANDATORY_TESTS.issubset(names):
        raise ValueError('Required codec, real-helper, production QML or Release guard tests are unregistered')
    return set(names)

def validate_results(path, inventory):
    cases = ET.parse(path).getroot().findall('.//testcase')
    names = [case.attrib['name'] for case in cases]
    if len(names) != len(set(names)) or set(names) != validate_inventory(inventory):
        raise ValueError('CTest did not execute exactly the complete registered inventory')
    failed = [c.attrib['name'] for c in cases if c.find('failure') is not None or c.find('error') is not None]
    skipped = [c.attrib['name'] for c in cases if c.find('skipped') is not None]
    allowed = {t['name'] for t in inventory['tests']
               if any(p['name'] == 'SKIP_RETURN_CODE' and p['value'] == 77 for p in t.get('properties', []))}
    if failed or set(skipped) - allowed or set(skipped) & MANDATORY_TESTS:
        raise ValueError('CTest failure, required-test skip or unqualified skip: ' + ', '.join(failed + skipped))
    return {'registered': len(names), 'executed': len(names) - len(skipped),
            'failures': 0, 'errors': 0, 'hardware_unavailable': skipped}

def software_icd(directory=Path('/usr/share/vulkan/icd.d'), machine=None):
    # Multilib hosts also install an i686 ICD; use the actual native test ABI.
    machine = machine or platform.machine()
    if machine not in {'x86_64', 'aarch64'}:
        raise ValueError('Unsupported native software Vulkan architecture')
    path = directory / ('lvp_icd.' + machine + '.json')
    if not path.is_file():
        raise ValueError('Native software Vulkan ICD is not installed')
    return path

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def leaves(root):
    return {str(p.relative_to(root)): {'link': os.readlink(p)} if p.is_symlink()
            else {'sha256': sha(p), 'mode': p.stat().st_mode & 0o7777}
            for p in sorted(root.rglob('*')) if p.is_file() or p.is_symlink()}

def source_bookend(root):
    def git(*args):
        return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()
    if git('status', '--porcelain', '--untracked-files=no'):
        raise ValueError('Require unchanged tracked source')
    names = git('ls-files', '--recurse-submodules').splitlines()
    return {'head': git('rev-parse', 'HEAD'), 'tree': git('rev-parse', 'HEAD^{tree}'),
            'files': {n: sha(root / n) for n in names if (root / n).is_file()}}

def audit_flags(build):
    tests, production = set(), set()
    for command in json.loads((build / 'compile_commands.json').read_text()):
        argv = command.get('arguments') or shlex.split(command['command'])
        output = command.get('output') or argv[argv.index('-o') + 1]
        parts = output.split('CMakeFiles/', 1)
        target = parts[1].split('.dir/', 1)[0] if len(parts) == 2 else None
        if target in ASSERTION_TARGETS and '/tests/' in command['file']:
            if '-DNDEBUG' not in argv or '-UNDEBUG' not in argv or argv.index('-UNDEBUG') < argv.index('-DNDEBUG'):
                raise ValueError('Release test assertions disabled: ' + str(target))
            tests.add(target)
        elif '-UNDEBUG' in argv:
            raise ValueError('Test-only assertion flag leaked: ' + str(target))
        if target in {'nova-deck', 'nova_deck_core'} and Path(command['file']).name in {'main.cpp', 'deck_stream_core.cpp'}:
            if '-DNDEBUG' not in argv or '-UNDEBUG' in argv:
                raise ValueError('Production Release assertion profile changed')
            production.add(target)
    if tests != ASSERTION_TARGETS or production != {'nova-deck', 'nova_deck_core'}:
        raise ValueError('Missing actual Release test or production compilation')
    return {'assertion_targets': sorted(tests), 'production_DNDEBUG': sorted(production)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', required=True, type=Path)
    parser.add_argument('--prefix-source-cache', type=Path)
    parser.add_argument('--moonlight-dir', type=Path)
    parser.add_argument('--jobs', type=int, default=3)
    parser.add_argument('--version-suffix', default='')
    args = parser.parse_args()
    if not 1 <= args.jobs <= 3:
        parser.error('jobs must be between 1 and 3')
    source = Path(__file__).resolve().parents[1]
    work = args.work_dir.resolve()
    if work.exists() or work.is_relative_to(source):
        parser.error('Require a fresh work directory outside the checkout')
    work.mkdir(parents=True)
    receipt = {'gate': 'FAIL', 'profile': 'Release; PyroWave ON; Vulkan ON; BUILD_TESTING ON',
               'jobs': args.jobs, 'ctest_parallel': 2, 'retries': 0,
               'physical_streaming_GPU_input_HDR_acceptance': False, 'commands': []}
    before = source_bookend(source)
    (work / 'source-before.json').write_text(json.dumps(before, indent=2) + '\n')
    donor_before = leaves(args.prefix_source_cache) if args.prefix_source_cache else None
    native_before = leaves(args.moonlight_dir) if args.moonlight_dir else None
    def run(argv, stage, env=None):
        receipt['stage'] = stage
        receipt['commands'].append(argv)
        print(stage + ': ' + shlex.join(argv), flush=True)
        with (work / (stage + '.log')).open('w') as log:
            result = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, env=env)
        if result.returncode:
            raise RuntimeError(stage + ' exited ' + str(result.returncode))
    try:
        command = [sys.executable, '-B', str(source / 'tools/build_nova_pyrowave_prefix.py'),
                   '--manifest', str(source / 'clients/deck/packaging/flatpak/modules/pyrowave.json'),
                   '--work-dir', str(work / 'dependency'), '--jobs', str(args.jobs)]
        if args.prefix_source_cache:
            command += ['--source-cache', str(args.prefix_source_cache)]
        run(command, 'dependency')
        prefix = work / 'dependency/prefix'
        prefix_before = leaves(prefix)
        env = os.environ | {
            'PKG_CONFIG_PATH': ':'.join(str(prefix / p) for p in ['share/pkgconfig', 'lib/pkgconfig', 'lib64/pkgconfig']),
            'LD_LIBRARY_PATH': ':'.join(str(prefix / p) for p in ['lib', 'lib64']),
            'QT_QPA_PLATFORM': 'offscreen', 'QT_QUICK_BACKEND': 'software',
            'LIBGL_ALWAYS_SOFTWARE': '1', 'GALLIUM_DRIVER': 'llvmpipe'}
        icd = software_icd()
        env |= {'VK_DRIVER_FILES': str(icd), 'VK_ICD_FILENAMES': str(icd)}
        build = work / 'build'
        command = ['cmake', '-S', str(source / 'clients/deck'), '-B', str(build), '-G', 'Ninja',
                   '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_TESTING=ON',
                   '-DNOVA_DECK_BUILD_PYROWAVE=ON', '-DNOVA_DECK_BUILD_VULKAN_STREAM=ON',
                   '-DNOVA_DECK_BUILD_QT_SHELL=ON', '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON',
                   '-DNOVA_DECK_VERSION_SUFFIX=' + args.version_suffix]
        if args.moonlight_dir:
            command += ['-DNOVA_DECK_MOONLIGHT_COMMON_C_DIR=' + str(args.moonlight_dir)]
        run(command, 'configure', env)
        inventory = json.loads(subprocess.check_output(
            ['ctest', '--test-dir', str(build), '--show-only=json-v1'], env=env, text=True))
        (work / 'ctest-inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
        receipt['registered_tests'] = sorted(validate_inventory(inventory))
        run(['cmake', '--build', str(build), '--parallel', str(args.jobs)], 'build', env)
        receipt['release_flags'] = audit_flags(build)
        run(['ctest', '--test-dir', str(build), '--parallel', '2', '--output-on-failure',
             '--no-tests=error', '--output-junit', str(work / 'codec-tests.xml')], 'ctest', env)
        receipt['tests'] = validate_results(work / 'codec-tests.xml', inventory)
        if leaves(prefix) != prefix_before:
            raise ValueError('Dependency prefix changed during the product tests')
        receipt['dependency_receipt_sha256'] = sha(work / 'dependency/DEPENDENCY-PREFIX-RECEIPT.json')
        receipt['gate'] = 'PASS_NATIVE_RELEASE_CODEC_ON_FULL_CTEST'
    except BaseException as error:
        receipt['error'] = str(error)
    finally:
        try:
            after = source_bookend(source)
            (work / 'source-after.json').write_text(json.dumps(after, indent=2) + '\n')
            if before != after or (donor_before is not None and donor_before != leaves(args.prefix_source_cache)) or (native_before is not None and native_before != leaves(args.moonlight_dir)):
                raise ValueError('Source or read-only dependency/native donor changed')
            receipt['bookends'] = 'PASS'
        except BaseException as error:
            receipt.update(gate='FAIL', bookends='FAIL', bookend_error=str(error))
        receipt |= {'head': before['head'], 'tree': before['tree']}
        (work / 'RECEIPT.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt), flush=True)
    return 0 if receipt['gate'] == 'PASS_NATIVE_RELEASE_CODEC_ON_FULL_CTEST' else 1

if __name__ == '__main__':
    raise SystemExit(main())
