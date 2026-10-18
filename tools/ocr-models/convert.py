"""Reproducible Lookitan ONNX -> Chimahon NCNN FP16 component bundles.

Adapted from Chimahon/chimahon-local-models tools/convert_paddle_ocr.py.
Requires pnnx 20260526 and ncnnoptimize 20260526. Never publishes releases.
"""
import argparse
import hashlib
import json
import subprocess
import time
import urllib.request
from pathlib import Path
import zipfile
import yaml

SOURCES = json.loads(Path(__file__).with_name('models.json').read_text())
VERSION = 'lookitan-ocr-v1'


def fetch(url, target):
    if target.exists():
        return target.read_bytes()
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            return data
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def adapt_interface(param, recognition):
    lines = param.read_text().strip().splitlines()
    layers, blobs = map(int, lines[1].split())
    rows = [line.split() for line in lines[2:]]
    output = 'out0'
    if recognition:
        last = rows[-1]
        if last[0] != 'Softmax' or last[2:4] != ['1', '1'] or last[5] != 'out0':
            raise ValueError('Unexpected recognition output; cannot expose CTC logits safely')
        output = last[4]
        rows.pop()
        layers -= 1
        blobs -= 1
    for row in rows:
        if row[:2] == ['Input', 'in0']:
            row[1] = 'input'
        for index in range(4, 4 + int(row[2]) + int(row[3])):
            if row[index] == 'in0':
                row[index] = 'input'
            elif row[index] == output:
                row[index] = 'output'
    param.write_text('7767517\n' + f'{layers} {blobs}\n' + '\n'.join(' '.join(row) for row in rows) + '\n')


def package(root, output):
    files = {str(p.relative_to(root)): sha(p.read_bytes()) for p in sorted(root.rglob('*')) if p.is_file()}
    with zipfile.ZipFile(output / f'{root.name}.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in files:
            entry = zipfile.ZipInfo(f'{root.name}/{name}', date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, (root / name).read_bytes())
    return {'sha256': sha((output / f'{root.name}.zip').read_bytes()), 'files': files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--pnnx', required=True)
    parser.add_argument('--ncnnoptimize', required=True)
    parser.add_argument('--only', choices=list(SOURCES))
    args = parser.parse_args()
    dist = args.work / 'dist'
    dist.mkdir(parents=True, exist_ok=True)
    manifest = {'version': VERSION, 'components': {}}
    for name, (repo, revision) in SOURCES.items():
        if args.only and name != args.only:
            continue
        source = args.work / 'source' / name
        metadata = json.loads(fetch(f'https://huggingface.co/api/models/{repo}/revision/{revision}?blobs=true', source / 'metadata.json'))
        model = source / 'inference.onnx'
        data = fetch(f'https://huggingface.co/{repo}/resolve/{revision}/inference.onnx', model)
        expected = next(s for s in metadata['siblings'] if s['rfilename'] == 'inference.onnx')['lfs']['sha256']
        if sha(data) != expected:
            raise ValueError(f'Source checksum mismatch: {name}')
        config = yaml.safe_load(fetch(f'https://huggingface.co/{repo}/resolve/{revision}/inference.yml', source / 'inference.yml'))
        target = args.work / 'bundles' / f'paddle_{name}'
        target.mkdir(parents=True, exist_ok=True)
        is_rec = name.startswith('rec_')
        shapes = ('[1,3,48,320]', '[1,3,48,640]') if is_rec else ('[1,3,960,960]', '[1,3,480,640]')
        with (source / 'conversion.log').open('w') as log:
            subprocess.run([args.pnnx, str(model), f'inputshape={shapes[0]}', f'inputshape2={shapes[1]}', 'fp16=1'], stdout=log, stderr=log, check=True)
            subprocess.run([args.ncnnoptimize, str(model.with_suffix('.ncnn.param')), str(model.with_suffix('.ncnn.bin')), str(target / 'model.param'), str(target / 'model.bin'), '65536'], stdout=log, stderr=log, check=True)
        adapt_interface(target / 'model.param', is_rec)
        if is_rec:
            chars = config['PostProcess']['character_dict']
            if any('\n' in c or '\r' in c or not c for c in chars):
                raise ValueError(f'Unsupported dictionary entries: {name}')
            (target / 'dict.txt').write_text('\n'.join(chars) + '\n')
        info = {'repository': repo, 'revision': revision, 'sourceSha256': expected, 'pnnx': '20260526', 'ncnn': '20260526', 'version': VERSION}
        (target / 'MODEL_INFO.json').write_text(json.dumps(info, indent=2) + '\n')
        manifest['components'][target.name] = package(target, dist)
        print(f'Converted {name}', flush=True)
    (dist / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
