"""Datasets for aircraft recognition.

Everything is reduced to a list of (image_path, class_index) "items" plus a
class list and a hierarchy (variant -> family -> manufacturer), so FGVC-Aircraft
and your own photo folders can be mixed freely.
"""

from __future__ import annotations

import csv
import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image
from torch.utils.data import Dataset

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
FGVC_DIR = "fgvc-aircraft-2013b"
FGVC_BANNER_PX = 20  # FGVC images carry a 20px copyright banner at the bottom.


@dataclass
class DataBundle:
    classes: list[str] = field(default_factory=list)
    # class name -> {"family": ..., "manufacturer": ...}
    hierarchy: dict[str, dict[str, str]] = field(default_factory=dict)
    train: list[tuple[str, int]] = field(default_factory=list)
    val: list[tuple[str, int]] = field(default_factory=list)

    def class_index(self, name: str, family: str = "", manufacturer: str = "") -> int:
        if name not in self.hierarchy:
            self.classes.append(name)
            self.hierarchy[name] = {"family": family or name, "manufacturer": manufacturer}
        return self.classes.index(name)


# --------------------------------------------------------------------------- FGVC


def _read_labels(path: Path) -> dict[str, str]:
    out = {}
    for line in path.read_text().splitlines():
        if line.strip():
            img_id, label = line.strip().split(" ", 1)
            out[img_id] = label
    return out


def _resize_one(job: tuple[str, str, int]) -> None:
    src, dst, short_side = job
    with Image.open(src) as im:
        im = im.convert("RGB")
        w, h = im.size
        im = im.crop((0, 0, w, h - FGVC_BANNER_PX))
        w, h = im.size
        scale = short_side / min(w, h)
        if scale < 1:
            im = im.resize((round(w * scale), round(h * scale)), Image.BILINEAR)
        im.save(dst, quality=93)


def prepare_fgvc(root: str | Path, short_side: int = 512, workers: int = 4) -> Path:
    """Download FGVC-Aircraft (once), crop the banner, shrink images for fast loading.

    Returns the prepared directory, which contains images/ and meta.json.
    """
    root = Path(root)
    out = root / "fgvc_prepared"
    if (out / "meta.json").exists():
        return out

    raw = root / FGVC_DIR / "data"
    if not raw.exists():
        from torchvision.datasets import FGVCAircraft

        print("Downloading FGVC-Aircraft (~2.7 GB)...")
        FGVCAircraft(str(root), split="trainval", download=True)

    variants = [v for v in (raw / "variants.txt").read_text().splitlines() if v.strip()]
    hierarchy: dict[str, dict[str, str]] = {}
    splits: dict[str, list[list[str]]] = {}
    for split in ("train", "val", "trainval", "test"):
        var = _read_labels(raw / f"images_variant_{split}.txt")
        fam = _read_labels(raw / f"images_family_{split}.txt")
        man = _read_labels(raw / f"images_manufacturer_{split}.txt")
        for img_id, v in var.items():
            hierarchy[v] = {"family": fam[img_id], "manufacturer": man[img_id]}
        splits[split] = sorted([img_id, v] for img_id, v in var.items())

    (out / "images").mkdir(parents=True, exist_ok=True)
    all_ids = sorted({img_id for items in splits.values() for img_id, _ in items})
    jobs = [
        (str(raw / "images" / f"{i}.jpg"), str(out / "images" / f"{i}.jpg"), short_side)
        for i in all_ids
        if not (out / "images" / f"{i}.jpg").exists()
    ]
    print(f"Preparing {len(jobs)} images -> {out / 'images'}", flush=True)
    # Threads, not processes: forking worker processes can freeze inside Jupyter/Colab kernels.
    # PIL releases the GIL while decoding/resizing, so threads are nearly as fast.
    with ThreadPoolExecutor(workers) as pool:
        for i, _ in enumerate(pool.map(_resize_one, jobs), 1):
            if i % 1000 == 0 or i == len(jobs):
                print(f"  {i}/{len(jobs)} images prepared", flush=True)

    meta = {"classes": variants, "hierarchy": hierarchy, "splits": splits}
    (out / "meta.json").write_text(json.dumps(meta, indent=1))
    return out


def add_fgvc(bundle: DataBundle, prepared: Path, train_split: str, val_split: str) -> None:
    meta = json.loads((prepared / "meta.json").read_text())
    for name in meta["classes"]:
        h = meta["hierarchy"][name]
        bundle.class_index(name, h["family"], h["manufacturer"])
    for split, target in ((train_split, bundle.train), (val_split, bundle.val)):
        for img_id, name in meta["splits"][split]:
            target.append((str(prepared / "images" / f"{img_id}.jpg"), bundle.classes.index(name)))


# ------------------------------------------------------------------- your folders


def add_folder(bundle: DataBundle, root: str | Path) -> None:
    """Add a folder laid out as root/{train,val}/<class name>/*.jpg.

    An optional root/hierarchy.csv (columns: class,family,manufacturer) fills in
    the family/manufacturer shown in the app. Class names that already exist
    (e.g. an FGVC variant like "A380") are merged into that class.
    """
    root = Path(root)
    hier: dict[str, dict[str, str]] = {}
    if (root / "hierarchy.csv").exists():
        with open(root / "hierarchy.csv", newline="") as f:
            for row in csv.DictReader(f):
                hier[row["class"]] = row
    for split, target in (("train", bundle.train), ("val", bundle.val)):
        split_dir = root / split
        if not split_dir.exists():
            continue
        for class_dir in sorted(p for p in split_dir.iterdir() if p.is_dir()):
            h = hier.get(class_dir.name, {})
            idx = bundle.class_index(class_dir.name, h.get("family", ""), h.get("manufacturer", ""))
            for img in sorted(class_dir.rglob("*")):
                if img.suffix.lower() in IMAGE_EXTS:
                    target.append((str(img), idx))


# ------------------------------------------------------------------------ dataset


class ItemDataset(Dataset):
    def __init__(self, items: list[tuple[str, int]], transform):
        self.items = items
        self.transform = transform

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, i):
        path, label = self.items[i]
        with Image.open(path) as im:
            return self.transform(im.convert("RGB")), label
