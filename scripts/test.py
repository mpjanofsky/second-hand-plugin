#!/usr/bin/env python3
"""Run every test in tests/ against the SDK without a device, then the build-tool tests.

A test is a class in tests/ whose name ends in Test and that has a main method; it fails by throwing (use
`assert`, run with -ea). The template's tools/test.py lists Hello World's tests by name, so plugins use this
runner instead and leave tools/ unchanged.
"""
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'tools'))
from android_sdk import android_platform

# Same defaults as scripts/check.sh (Homebrew JDK 17, Android Studio's SDK) so this runs on its own too.
if not os.environ.get('JAVA_HOME') and Path('/opt/homebrew/opt/openjdk@17').exists():
    os.environ['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@17'
if not os.environ.get('ANDROID_HOME') and (Path.home() / 'Library/Android/sdk').exists():
    os.environ['ANDROID_HOME'] = str(Path.home() / 'Library/Android/sdk')
java_home = os.environ.get('JAVA_HOME')


def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name


# The SDK's overlay classes use Android views. Tests never draw one, so the platform's stub classes are
# enough to compile and load them.
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error

# Everything in tests/ is compiled (shared helpers such as kit/FakeHost too); only *Test classes are run.
tests = sorted((root / 'tests').rglob('*Test.java'))
sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')), *sorted((root / 'tests').rglob('*.java'))]
failed = []
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-nowarn', '-cp', platform, '-d', directory, *map(str, sources)], check=True)
    for test in tests:
        package = re.search(r'^\s*package\s+([\w.]+)\s*;', test.read_text(), re.M)
        name = (package.group(1) + '.' if package else '') + test.stem
        # Fixtures are read relative to the repository root.
        run = subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([directory, platform]), name], cwd=root)
        if run.returncode:
            failed.append(name)

for script in sorted((root / 'tools').glob('test_*.py')):
    if subprocess.run([sys.executable, str(script)]).returncode:
        failed.append(script.name)

if failed:
    raise SystemExit('failed: ' + ', '.join(failed))
print('%d tests passed' % len(tests))
