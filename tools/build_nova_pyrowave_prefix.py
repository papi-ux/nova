#!/usr/bin/env python3
"""Build a private CI prefix from the four pins in the shipped Flatpak module."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import tarfile


def read_module(path):
    module = json.loads(path.read_text())
    sources = module['sources']
    destinations = ['', 'Granite', 'Granite/third_party/volk',
                    'Granite/third_party/khronos/vulkan-headers']
    if len(sources) != 4 or [s.get('dest', '') for s in sources] != destinations:
        raise ValueError('Require the four complete PyroWave dependency sources')
    for source in sources:
        if source.get('type') != 'git' or source.get('disable-submodules') is not True:
            raise ValueError('Require explicit Git pins without implicit submodules')
        if not re.fullmatch(r'[0-9a-f]{40}', source.get('commit', '')):
            raise ValueError('Require an exact full dependency commit')
        if not source.get('url', '').startswith('https://github.com/'):
            raise ValueError('Require the shipped HTTPS source URL')
    if module['config-opts'] != ['-DCMAKE_BUILD_TYPE=Release', '-DPYROWAVE_DEVEL=OFF', '-DPYROWAVE_UTILS=OFF']:
        raise ValueError('Require the shipped codec library build profile')
    return module


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def export(repo, commit, destination):
    destination.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(['git', '-C', str(repo), 'archive', '--format=tar', commit], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            archive.extractall(destination, filter='data')
    finally:
        process.stdout.close()
    if process.wait() != 0:
        raise RuntimeError('Pinned source archive failed')


def build(args):
    module = read_module(args.manifest)
    work = args.work_dir.resolve()
    if work.exists():
        raise ValueError('Require a fresh owned dependency work directory')
    work.mkdir(parents=True)
    prefix = work / 'prefix'
    source_root = work / 'source'
    receipt = {'gate': 'FAIL', 'manifest_sha256': hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
               'sources': [], 'jobs': args.jobs, 'build_profile': module['config-opts']}
    try:
        for index, entry in enumerate(module['sources']):
            destination = entry.get('dest', '')
            if args.source_cache:
                repo = args.source_cache.resolve() / destination
                if git(repo, 'rev-parse', 'HEAD') != entry['commit'] or git(repo, 'status', '--porcelain', '--untracked-files=no'):
                    raise ValueError('Source cache does not match the clean exact dependency pin')
            else:
                repo = work / 'repos' / str(index)
                repo.mkdir(parents=True)
                subprocess.run(['git', 'init', '--quiet', str(repo)], check=True)
                subprocess.run(['git', '-C', str(repo), 'fetch', '--no-tags', '--depth=1', entry['url'], entry['commit']], check=True)
                if git(repo, 'rev-parse', 'FETCH_HEAD') != entry['commit']:
                    raise ValueError('Fetched dependency did not match its pin')
            export(repo, entry['commit'], source_root / destination)
            receipt['sources'].append({'url': entry['url'], 'commit': entry['commit'], 'destination': destination,
                                       'tree': git(repo, 'rev-parse', entry['commit'] + '^{tree}')})
        commands = [
            ['cmake', '-S', str(source_root), '-B', str(work / 'build'), '-G', 'Ninja',
             *module['config-opts'], '-DCMAKE_INSTALL_PREFIX=' + str(prefix)],
            ['cmake', '--build', str(work / 'build'), '--parallel', str(args.jobs)],
            ['cmake', '--install', str(work / 'build')],
        ]
        receipt['commands'] = commands
        for command in commands:
            subprocess.run(command, check=True)
        env = os.environ | {'PKG_CONFIG_PATH': ':'.join(str(prefix / n) for n in
                    ['share/pkgconfig', 'lib/pkgconfig', 'lib64/pkgconfig'])}
        version = subprocess.check_output(['pkg-config', '--modversion', 'pyrowave-shared'], env=env, text=True).strip()
        libdir = Path(subprocess.check_output(['pkg-config', '--variable=libdir', 'pyrowave-shared'], env=env, text=True).strip())
        library = (libdir / 'libpyrowave-shared.so').resolve()
        if version != '0.6.0' or not library.is_relative_to(prefix) or not library.is_file():
            raise ValueError('Installed codec API or prefix identity differs')
        data = library.read_bytes()
        expected_machine = {'x86_64': 62, 'aarch64': 183}.get(platform.machine())
        if expected_machine is None or data[:6] != b'\x7fELF\x02\x01' or int.from_bytes(data[18:20], 'little') != expected_machine:
            raise ValueError('Installed shared library is not the native 64-bit ELF architecture')
        receipt.update(gate='PASS_PINNED_NATIVE_PYROWAVE_PREFIX', version=version,
                       library=str(library), elf_machine=expected_machine,
                       installed_files={str(p.relative_to(prefix)): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in sorted(prefix.rglob('*')) if p.is_file() and not p.is_symlink()})
        return prefix
    except BaseException as error:
        receipt['error'] = str(error)
        raise
    finally:
        (work / 'DEPENDENCY-PREFIX-RECEIPT.json').write_text(json.dumps(receipt, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=Path('clients/deck/packaging/flatpak/modules/pyrowave.json'))
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--source-cache', type=Path, help='Read-only clean checkout containing the four exact pins')
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 8:
        parser.error('jobs must be between 1 and 8')
    print(build(args))


if __name__ == '__main__':
    main()
