"""Verify the preview package and its persistent public signing certificate."""
import argparse
from pathlib import Path
import os
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    expected = Path(__file__).with_name('preview-signing.sha256').read_text().strip()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise SystemExit('Invalid expected preview signing certificate')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise SystemExit('Android SDK is required to verify the APK')
    versions = sorted((Path(sdk) / 'build-tools').iterdir(),
                      key=lambda p: tuple(map(int, re.findall(r'\d+', p.name))), reverse=True)
    tools = next((p for p in versions if (p / 'apksigner').is_file() and (p / 'aapt2').is_file()), None)
    if tools is None:
        raise SystemExit('Android apksigner and aapt2 are required')
    signature = subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)],
                               capture_output=True, text=True)
    if signature.returncode:
        raise SystemExit('APK signature verification failed')
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signature.stdout, re.M)
    if [value.lower() for value in certificates] != [expected]:
        raise SystemExit('APK does not use the persistent preview signing certificate')
    badging = subprocess.run([str(tools / 'aapt2'), 'dump', 'badging', str(args.apk)],
                            capture_output=True, text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging.stdout, re.M)
    if badging.returncode or not package or package[1] != 'app.chimahon.beta':
        raise SystemExit('APK has an unexpected preview package identifier')
    print(f'Verified preview APK: {args.apk.name}')
    print(f'Package: {package[1]}, versionCode: {package[2]}')
    print(f'Signer SHA-256: {expected}')


if __name__ == '__main__':
    main()
