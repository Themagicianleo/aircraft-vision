"""Helpers that mirror exactly what the phone apps do with the exported model."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps


def preprocess(path: str | Path, size: int) -> np.ndarray:
    """Short side -> size, centre crop, RGB float32 [0,1], NCHW. Same as the Android app."""
    with Image.open(path) as im:
        im = ImageOps.exif_transpose(im).convert("RGB")
        w, h = im.size
        scale = size / min(w, h)
        im = im.resize((max(size, round(w * scale)), max(size, round(h * scale))), Image.BILINEAR)
        w, h = im.size
        left, top = (w - size) // 2, (h - size) // 2
        im = im.crop((left, top, left + size, top + size))
    return (np.asarray(im, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]


class OnnxClassifier:
    def __init__(self, model_path: str | Path, labels_path: str | Path | None = None):
        import onnxruntime as ort

        model_path = Path(model_path)
        labels_path = Path(labels_path) if labels_path else model_path.with_name("labels.json")
        self.labels = json.loads(labels_path.read_text())
        self.size = self.labels["img_size"]
        self.session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name

    def probs(self, path: str | Path) -> np.ndarray:
        return self.session.run(None, {self.input_name: preprocess(path, self.size)})[0][0]

    def top_k(self, path: str | Path, k: int = 5) -> list[dict]:
        p = self.probs(path)
        return [{**self.labels["classes"][i], "confidence": float(p[i])} for i in np.argsort(-p)[:k]]
