"""Compare actual NCNN FP16 outputs/CTC decoding to the pinned ONNX sources."""
import argparse
import json
from pathlib import Path
import numpy as np
import ncnn
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
from convert import SOURCES

SAMPLES = {
    'rec_tiny': 'Olá ação français Straße Español Łódź',
    'rec_small': '中文 日本語 România șț',
    'rec_cyrillic': 'Привет мир Україна',
    'rec_arabic': 'مرحبا بالعالم',
    'rec_el': 'Καλημέρα κόσμε',
    'rec_korean': '안녕하세요 한국어',
}


def softmax(value):
    value = np.exp(value - value.max(axis=-1, keepdims=True))
    return value / value.sum(axis=-1, keepdims=True)


def decode(value, chars):
    previous = -1
    result = []
    for index in value.argmax(axis=-1):
        if index != previous and index != 0:
            result.append(chars[index])
        previous = index
    return ''.join(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--font', type=Path, required=True)
    parser.add_argument('--arabic-font', type=Path, required=True)
    args = parser.parse_args()
    reports = {}
    for name in SOURCES:
        root = args.work / 'bundles' / f'paddle_{name}'
        session_options = ort.SessionOptions()
        session_options.intra_op_num_threads = 2
        session_options.log_severity_level = 3
        session = ort.InferenceSession(str(args.work / 'source' / name / 'inference.onnx'), session_options, providers=['CPUExecutionProvider'])
        net = ncnn.Net()
        net.opt.num_threads = 2
        net.opt.use_vulkan_compute = False
        assert net.load_param(str(root / 'model.param')) == 0
        assert net.load_model(str(root / 'model.bin')) == 0
        is_rec = name.startswith('rec_')
        chars = [''] + (root / 'dict.txt').read_text().splitlines() + [' '] if is_rec else None
        cases = []
        shapes = [(48, w) for w in [16, 128, 320, 640, 1280]] if is_rec else [(128, 128), (480, 640), (960, 960)]
        for height, width in shapes:
            data = np.random.default_rng(42).uniform(-1, 1, (1, 3, height, width)).astype(np.float32)
            cases.append(('random', data))
        if is_rec:
            font = ImageFont.truetype(str(args.arabic_font if name == 'rec_arabic' else args.font), 30)
            image = Image.new('RGB', (900, 48), 'white')
            ImageDraw.Draw(image).text((4, 0), SAMPLES[name], font=font, fill='black')
            bounds = ImageDraw.Draw(image).textbbox((4, 0), SAMPLES[name], font=font)
            image = image.crop((0, 0, min(900, bounds[2] + 12), 48))
            data = np.asarray(image).astype(np.float32)[:, :, ::-1] / 127.5 - 1
            cases.append(('text', data.transpose(2, 0, 1)[None].copy()))
        results = []
        for kind, data in cases:
            expected = session.run(None, {session.get_inputs()[0].name: data})[0].squeeze()
            with net.create_extractor() as extractor:
                assert extractor.input('input', ncnn.Mat(data[0]).clone()) == 0
                code, result = extractor.extract('output')
                assert code == 0
                result = np.array(result).copy().squeeze()
            assert result.shape == expected.shape, (name, result.shape, expected.shape)
            item = {'kind': kind, 'shape': list(data.shape), 'meanAbsError': float(np.abs(result - expected).mean())}
            assert np.isfinite(result).all()
            if is_rec:
                assert result.shape[-1] == len(chars), (name, result.shape, len(chars))
                probabilities = softmax(result)
                item['argmaxAgreement'] = float((result.argmax(-1) == expected.argmax(-1)).mean())
                item['meanProbabilityError'] = float(np.abs(probabilities - expected).mean())
                item['meanAbsError'] = item['meanProbabilityError']
                item['onnxText'] = decode(expected, chars)
                item['ncnnText'] = decode(result, chars)
                assert item['meanProbabilityError'] < 0.005, (name, item)
                assert item['argmaxAgreement'] >= 0.9, (name, item)
                if kind == 'text':
                    assert item['onnxText'] == item['ncnnText'], (name, item)
            else:
                assert item['meanAbsError'] < 0.005, (name, item)
            results.append(item)
        reports[name] = results
        print(f'Validated {name}: {len(results)} cases', flush=True)
    (args.work / 'dist' / 'validation.json').write_text(json.dumps({'runtime': 'desktop CPU; does not establish Android accuracy', 'cases': reports}, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
