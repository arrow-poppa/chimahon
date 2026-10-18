"""Verify the preview package and its persistent public signing certificate."""
import argparse
from pathlib import Path
import os
import re
import subprocess
import tempfile


def android_tools():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise SystemExit('Android SDK is required to verify the APK')
    versions = sorted((Path(sdk) / 'build-tools').iterdir(),
                      key=lambda p: tuple(map(int, re.findall(r'\d+', p.name))), reverse=True)
    tools = next((p for p in versions if (p / 'apksigner').is_file() and (p / 'aapt2').is_file()), None)
    if tools is None:
        raise SystemExit('Android apksigner and aapt2 are required')
    return Path(sdk), tools


def check_certificate(output, expected):
    # APK v3.1 reports SDK ranges instead of ordinal signer numbers.
    certificates = set(value.lower() for value in re.findall(
        r'^Signer (?:#\d+|\([^\r\n]+\)) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$', output, re.M))
    if not certificates:
        raise SystemExit('Could not identify APK signer certificates in apksigner output')
    if certificates != {expected}:
        raise SystemExit('Unexpected APK signing certificates: ' + ', '.join(sorted(certificates)))


def check_signing_report(output, expected):
    blocks = re.split(r'^Variant:\s*', output, flags=re.M)
    preview = next((block for block in blocks if block.splitlines() and block.splitlines()[0].strip() == 'preview'), '')
    fingerprint = re.search(r'^SHA-256:\s*([0-9a-fA-F:]+)\s*$', preview, re.M)
    if not fingerprint or fingerprint[1].replace(':', '').lower() != expected:
        raise SystemExit('Gradle preview signing configuration does not match the persistent certificate')
    print('Gradle preview signing configuration matches the persistent certificate')


def verify_apk(apk, tools, expected):
    signature = subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)],
                               capture_output=True, text=True)
    if signature.returncode:
        raise SystemExit('APK signature verification failed')
    try:
        check_certificate(signature.stdout + signature.stderr, expected)
    except SystemExit:
        print(f'apksigner output sizes: stdout={len(signature.stdout)}, stderr={len(signature.stderr)}', flush=True)
        for line in (signature.stdout + signature.stderr).splitlines():
            if 'SHA-256' in line or 'SHA256' in line:
                print(line, flush=True)
        raise
    badging = subprocess.run([str(tools / 'aapt2'), 'dump', 'badging', str(apk)],
                            capture_output=True, text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging.stdout, re.M)
    if badging.returncode or not package or package[1] != 'app.chimahon.beta':
        raise SystemExit('APK has an unexpected preview package identifier')
    print(f'Verified preview APK: {apk.name}')
    print(f'Package: {package[1]}, versionCode: {package[2]}')
    print(f'Signer SHA-256: {expected}')


def probe(sdk, tools, expected):
    keystore = os.environ.get('CHIMAHON_PREVIEW_KEYSTORE_PATH')
    if not keystore:
        raise SystemExit('Persistent preview keystore is required for the signing probe')
    platforms = sorted((sdk / 'platforms').glob('*/android.jar'),
                       key=lambda p: tuple(map(int, re.findall(r'\d+', p.parent.name))), reverse=True)
    if not platforms:
        raise SystemExit('Android platform is required for the signing probe')
    with tempfile.TemporaryDirectory(prefix='chimahon-signing-probe-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        root = Path(directory)
        manifest = root / 'AndroidManifest.xml'
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            'package="app.chimahon.beta" android:versionCode="1" android:versionName="signing-probe">'
                            '<uses-sdk android:minSdkVersion="26"/><application android:label="Signing probe"/></manifest>')
        unsigned = root / 'unsigned.apk'
        signed = root / 'signed.apk'
        subprocess.run([str(tools / 'aapt2'), 'link', '-I', str(platforms[0]), '--manifest', str(manifest),
                        '-o', str(unsigned)], check=True, capture_output=True)
        subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', keystore, '--ks-pass', 'pass:android',
                        '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey',
                        '--out', str(signed), str(unsigned)], check=True, capture_output=True)
        verify_apk(signed, tools, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path, nargs='?')
    parser.add_argument('--probe', action='store_true')
    parser.add_argument('--signing-report', type=Path)
    args = parser.parse_args()
    if not args.probe and args.apk is None and args.signing_report is None:
        parser.error('an APK path, --probe, or --signing-report is required')
    expected = Path(__file__).with_name('preview-signing.sha256').read_text().strip()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise SystemExit('Invalid expected preview signing certificate')
    if args.signing_report is not None:
        check_signing_report(args.signing_report.read_text(), expected)
        return
    sdk, tools = android_tools()
    if args.probe:
        probe(sdk, tools, expected)
    else:
        verify_apk(args.apk, tools, expected)


if __name__ == '__main__':
    main()
