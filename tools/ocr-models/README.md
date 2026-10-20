# OCR models for Chimahon

The settings and reader source menu select recognition models manually. The
OCR detector selector in settings and the reader menu selects PP-OCRv6 Tiny (default)
or Small for all new recognizers, including Automatic mode when its engine uses them.
Both run on CPU. The existing Manga option keeps its own detector and thresholds.
The dictionary profile still determines language and reading rules.

`models.json` pins all eight ONNX models to immutable Hugging Face revisions:
two detectors and six recognizers. Conversion adapts
Chimahon's existing pnnx / ncnnoptimize pipeline, including dynamic dimensions,
`input` / `output` blob names and removal of the recognition Softmax for JNI CTC
logits. Each recognizer has its own matching UTF-8 character dictionary.

The Android runtime is the existing official library, pinned to a source commit in
`runtime.py`, packaged separately and shared. Previously installed Manga libraries
can be reused. No ONNX runtime is added to the Android application.

The `Build OCR models` workflow installs pinned conversion tools, converts all
models, compares their outputs and decoded text to the ONNX originals, and creates
an immutable `ocr-models-v1` release on the fork. SHA-256 hashes cover each archive
and all extracted files. The public release tag is separate from the content
version so moving hosting preserves installed components and cached results.
To change weights, bump the content version in Python and `PaddleModels.kt` and
use a new release tag; never replace the assets of an existing model release.

Local reproduction (use a virtualenv):

```sh
pip install --no-deps pnnx==20260526
pip install ncnn==1.0.20260526 onnx==1.23.2 onnxruntime==1.31.0 PyYAML==6.0.3 pillow==13.0.0 numpy==2.5.4
python tools/ocr-models/convert.py --work WORK --pnnx PNNX_BINARY --ncnnoptimize NCNNOPTIMIZE_BINARY
python tools/ocr-models/runtime.py --work WORK
python tools/ocr-models/validate.py --work WORK --font CJK_FONT --arabic-font ARABIC_FONT
```

Use NCNN / pnnx 20260526. `PNNX_BINARY` is the standalone executable inside the
installed pnnx package; its Python export API is not needed. The validation report
covers desktop CPU comparisons, dynamic image sizes and widths, class counts, and
CTC decoding of synthetic samples. It does not establish Android accuracy or speed.

Paddle model weights are from the official PaddlePaddle model repositories. The
existing Manga models and JNI runtime retain their upstream provenance and notices;
no model training or unpublished user images are used by these scripts.
