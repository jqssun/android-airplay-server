#!/usr/bin/env python3
"""Host regressions against the pinned UxPlay source; no Android SDK required.

Uses real buffer/RTP/timeline code. Crypto and sender clock synchronization are
stubbed: these tests validate sequencing and recovery, not decoding or devices.
"""
import argparse
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CPP = ROOT / 'app/src/main/cpp'
UX = CPP / 'third_party/UxPlay'


def run(*args, cwd=None):
    print('+', ' '.join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', action='store_true', help='run against unpatched upstream (expected failure)')
    parser.add_argument('--no-sanitize', action='store_true', help='disable host sanitizers')
    parser.add_argument('--sanitize', default='undefined', help='sanitizers (default: undefined; optionally address,undefined)')
    parser.add_argument('--udp', action='store_true', help='also test real local UDP sockets')
    args = parser.parse_args()
    if args.udp:
        os.environ['AIRPLAY_TEST_UDP'] = '1'
    rev = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD:app/src/main/cpp/third_party/UxPlay'], text=True).strip()
    with tempfile.TemporaryDirectory(prefix='airplay-native-') as tmp:
        tmp = Path(tmp)
        archive = subprocess.check_output(['git', '-C', str(UX), 'archive', rev, 'lib'])
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            for entry in tar.getmembers():
                if entry.isfile():
                    dest = tmp / entry.name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(tar.extractfile(entry).read())
        if not args.baseline:
            # Isolate from the caller's Git checkout; verify the complete ordered
            # patch series exactly as Gradle's applyUxplayPatches task does.
            run('git', 'init', '-q', str(tmp))
            for patch in sorted((CPP / 'patches/UxPlay').glob('*.patch')):
                run('git', 'apply', '--unidiff-zero', str(patch), cwd=tmp)
        native = ROOT / 'tests/native'
        common = ['-g', '-fno-omit-frame-pointer']
        if not args.no_sanitize:
            common += [f'-fsanitize={args.sanitize}', '-fno-sanitize-recover=all']
        platform = (['-include', 'libkern/OSByteOrder.h',
                     '-Dhtonll=OSSwapHostToBigInt64', '-Dntohll=OSSwapBigToHostInt64']
                    if sys.platform == 'darwin' else ['-D_DEFAULT_SOURCE'])
        run('clang', '-std=c17', '-D_POSIX_C_SOURCE=200809L', *common,
            *platform,
            '-I', str(tmp / 'lib'), str(native / 'raop_audio_test.c'),
            str(tmp / 'lib/byteutils.c'), str(tmp / 'lib/logger.c'),
            str(tmp / 'lib/netutils.c'), '-pthread', '-lm', '-o', str(tmp / 'raop_test'))
        run(str(tmp / 'raop_test'))
        run('clang++', '-std=c++17', *common, '-Wall', '-Wextra', '-Werror',
            '-I', str(CPP), str(native / 'timeline_test.cpp'), '-pthread',
            '-o', str(tmp / 'timeline_test'))
        run(str(tmp / 'timeline_test'))


if __name__ == '__main__':
    main()
