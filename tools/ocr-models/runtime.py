"""Package the existing official CPU JNI runtime; no native code is replaced."""
import argparse
import json
from convert import fetch, package, VERSION
from pathlib import Path

REVISION = 'a77fe2700a443ba86ce12fd3b083aaa7bdbba345'

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work', type=Path, required=True)
args = parser.parse_args()
root = args.work / 'bundles' / 'paddle_runtime'
for abi in ['arm64-v8a', 'armeabi-v7a']:
    for library in ['libpaddle_ocr.so', 'libomp.so', 'libc++_shared.so']:
        fetch(f'https://raw.githubusercontent.com/Chimahon/chimahon-local-models/{REVISION}/paddle_ocr/lib/{abi}/{library}', root / 'lib' / abi / library)
(root / 'MODEL_INFO.json').write_text(json.dumps({'version': VERSION, 'source': 'Chimahon/chimahon-local-models', 'revision': REVISION}, indent=2) + '\n')
dist = args.work / 'dist'
manifest_path = dist / 'manifest.json'
manifest = json.loads(manifest_path.read_text())
manifest['components'][root.name] = package(root, dist)
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
