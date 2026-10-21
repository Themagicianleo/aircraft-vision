"""Measure an exported ONNX model's accuracy using the same preprocessing as the app.

    python evaluate.py --onnx exported/aircraft.onnx --data-root data
    python evaluate.py --onnx exported/aircraft_int8.onnx --data-root data
    python evaluate.py --onnx exported/aircraft.onnx --no-fgvc --extra-data my_photos
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

from av.data import DataBundle, add_fgvc, add_folder, prepare_fgvc
from av.mobile import OnnxClassifier


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--onnx", required=True)
    p.add_argument("--labels", help="defaults to labels.json next to the model")
    p.add_argument("--data-root", default="data")
    p.add_argument("--no-fgvc", action="store_true")
    p.add_argument("--val-split", default="test", choices=["val", "test"])
    p.add_argument("--extra-data", nargs="*", default=[])
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--json", help="also write the metrics to this file")
    args = p.parse_args()

    clf = OnnxClassifier(args.onnx, args.labels)
    bundle = DataBundle()
    if not args.no_fgvc:
        add_fgvc(bundle, prepare_fgvc(args.data_root), "trainval", args.val_split)
    for folder in args.extra_data:
        add_folder(bundle, folder)

    names = [c["name"] for c in clf.labels["classes"]]
    items = [(path, bundle.classes[i]) for path, i in bundle.val if bundle.classes[i] in names]
    if args.limit:
        items = items[: args.limit]
    fam = {c["name"]: c["family"] for c in clf.labels["classes"]}
    man = {c["name"]: c["manufacturer"] for c in clf.labels["classes"]}

    top1 = top5 = fam_ok = man_ok = 0
    t0 = time.time()
    for path, truth in items:
        order = np.argsort(-clf.probs(path))[:5]
        pred = names[order[0]]
        top1 += pred == truth
        top5 += truth in {names[i] for i in order}
        fam_ok += fam[pred] == fam[truth]
        man_ok += man[pred] == man[truth]
    n = max(len(items), 1)
    if args.json:
        Path(args.json).write_text(json.dumps({"images": len(items), "top1": top1 / n, "top5": top5 / n,
                                               "family": fam_ok / n, "manufacturer": man_ok / n}, indent=1))
    print(f"{len(items)} images  top1 {top1 / n:.3f}  top5 {top5 / n:.3f}  family {fam_ok / n:.3f}  "
          f"manufacturer {man_ok / n:.3f}  ({1000 * (time.time() - t0) / n:.0f} ms/image incl. decode)")


if __name__ == "__main__":
    main()
