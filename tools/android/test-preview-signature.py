"""Check real apksigner output formats without exposing certificate subjects."""
from pathlib import Path
import runpy
import unittest

module = runpy.run_path(str(Path(__file__).with_name('verify-preview-apk.py')))
check_certificate = module['check_certificate']
check_signing_report = module['check_signing_report']
EXPECTED = 'a' * 64
OTHER = 'b' * 64


class PreviewCertificateTest(unittest.TestCase):
    def test_ordinal_signer(self):
        check_certificate(f'Signer #1 certificate SHA-256 digest: {EXPECTED}\n', EXPECTED)

    def test_sdk_ranges_and_repeated_certificate(self):
        output = (f'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {EXPECTED}\n'
                  f'Signer (minSdkVersion=28, maxSdkVersion=32) certificate SHA-256 digest: {EXPECTED}\n')
        check_certificate(output, EXPECTED)

    def test_dev_release_and_uppercase_digest(self):
        check_certificate(f'Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647) '
                          f'certificate SHA-256 digest: {EXPECTED.upper()}\n', EXPECTED)

    def test_source_stamp_does_not_replace_the_apk_signer(self):
        with self.assertRaises(SystemExit):
            check_certificate(f'Source Stamp Signer certificate SHA-256 digest: {EXPECTED}\n', EXPECTED)

    def test_wrong_or_additional_signer_is_rejected(self):
        for output in (f'Signer #1 certificate SHA-256 digest: {OTHER}\n',
                       f'Signer #1 certificate SHA-256 digest: {EXPECTED}\nSigner #2 certificate SHA-256 digest: {OTHER}\n'):
            with self.assertRaises(SystemExit):
                check_certificate(output, EXPECTED)

    def test_empty_or_unrecognized_output_is_rejected(self):
        for output in ('', f'Signer #1 public key SHA-256 digest: {EXPECTED}\n'):
            with self.assertRaises(SystemExit):
                check_certificate(output, EXPECTED)

    def test_gradle_preview_is_checked_independently_of_debug(self):
        fingerprint = ':'.join(EXPECTED[i:i+2] for i in range(0, 64, 2)).upper()
        check_signing_report(f'Variant: debug\nSHA-256: {OTHER}\n----------\n'
                             f'Variant: preview\nConfig: forkPreview\nSHA-256: {fingerprint}\n----------\n', EXPECTED)

    def test_missing_or_mismatched_gradle_preview_is_rejected(self):
        for output in ('Variant: debug\nSHA-256: ' + EXPECTED, 'Variant: preview\nSHA-256: ' + OTHER):
            with self.assertRaises(SystemExit):
                check_signing_report(output, EXPECTED)


if __name__ == '__main__':
    unittest.main()
