"""Export a trained checkpoint to the files the mobile apps load.

    python export.py --checkpoint runs/effb0/last.pt --out exported
    python export.py --checkpoint runs/effb0/last.pt --out exported --int8 --calib-dir data/fgvc_prepared/images

Produces:
    aircraft.onnx       fp32 model (RGB [0,1] NCHW in, probabilities out)
    aircraft_int8.onnx  optional, ~4x smaller; check its accuracy with evaluate.py
    labels.json         class names + family/manufacturer + input size
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import numpy as np
import torch

from av.data import IMAGE_EXTS
from av.mobile import preprocess
from av.model import MobileWrapper, create_model


def export_onnx(wrapper: torch.nn.Module, size: int, path: Path) -> None:
    dummy = torch.rand(1, 3, size, size)
    # The TorchScript-based exporter produces the plain, widely supported op set that
    # ONNX Runtime Mobile runs everywhere; fixed batch size 1 is all the apps need.
    torch.onnx.export(wrapper, dummy, str(path), input_names=["image"], output_names=["probs"],
                      opset_version=17, dynamo=False)


def verify(wrapper: torch.nn.Module, size: int, path: Path) -> float:
    import onnxruntime as ort

    x = torch.rand(1, 3, size, size)
    with torch.no_grad():
        ref = wrapper(x).numpy()
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    got = sess.run(None, {"image": x.numpy()})[0]
    return float(np.abs(ref - got).max())


def quantize_int8(src: Path, dst: Path, calib_dir: Path, size: int, n: int = 300) -> None:
    from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static
    from onnxruntime.quantization.shape_inference import quant_pre_process

    images = sorted(p for p in calib_dir.rglob("*") if p.suffix.lower() in IMAGE_EXTS)
    random.Random(0).shuffle(images)
    images = images[:n]
    if not images:
        raise SystemExit(f"No calibration images in {calib_dir}")

    class Reader(CalibrationDataReader):
        def __init__(self):
            self.it = iter(images)

        def get_next(self):
            p = next(self.it, None)
            return None if p is None else {"image": preprocess(p, size)}

    pre = dst.with_suffix(".pre.onnx")
    quant_pre_process(str(src), str(pre))
    print(f"Calibrating int8 on {len(images)} images...")
    quantize_static(str(pre), str(dst), Reader(), quant_format=QuantFormat.QDQ, per_channel=True,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8)
    pre.unlink()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--out", default="exported")
    p.add_argument("--int8", action="store_true")
    p.add_argument("--calib-dir", help="folder of representative photos for int8 calibration")
    args = p.parse_args()

    ck = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    meta = ck["meta"]
    size = meta["img_size"]
    model = create_model(meta["model"], len(meta["classes"]), pretrained=False)
    model.load_state_dict(ck["model"])
    wrapper = MobileWrapper(model, meta["mean"], meta["std"]).eval()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    onnx_path = out / "aircraft.onnx"
    export_onnx(wrapper, size, onnx_path)
    err = verify(wrapper, size, onnx_path)
    print(f"{onnx_path}  {onnx_path.stat().st_size / 1e6:.1f} MB  (max |torch - onnx| = {err:.2e})")
    if err > 1e-3:
        raise SystemExit("ONNX output does not match PyTorch; not exporting labels.")

    labels = {
        "model": meta["model"],
        "img_size": size,
        "input": "image: float32 [1,3,H,W], RGB in [0,1]; short side scaled to img_size then centre-cropped",
        "output": "probs: float32 [1,num_classes], softmax probabilities",
        "classes": [{"name": c, **meta["hierarchy"][c]} for c in meta["classes"]],
    }
    (out / "labels.json").write_text(json.dumps(labels, indent=1))

    if args.int8:
        if not args.calib_dir:
            raise SystemExit("--int8 needs --calib-dir")
        q = out / "aircraft_int8.onnx"
        quantize_int8(onnx_path, q, Path(args.calib_dir), size)
        print(f"{q}  {q.stat().st_size / 1e6:.1f} MB")

    print(f"Copy aircraft.onnx (or the int8 one, renamed) and labels.json into android/app/src/main/assets/")


if __name__ == "__main__":
    main()
