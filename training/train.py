"""Fine-tune a mobile-sized ImageNet model to recognise aircraft types.

Examples:
    # FGVC-Aircraft (100 variants), downloaded automatically
    python train.py --data-root data --out runs/effb0

    # FGVC plus your own photos (adds new types such as "787-9", "A350-900")
    python train.py --data-root data --extra-data my_photos --out runs/effb0_plus
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from pathlib import Path

import torch
from torch import nn
from torch.utils.data import DataLoader

from av.data import DataBundle, ItemDataset, add_fgvc, add_folder, prepare_fgvc
from av.model import DEFAULT_MODEL, create_model, eval_transform, norm_stats, train_transform


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--data-root", default="data", help="where FGVC-Aircraft is downloaded/prepared")
    p.add_argument("--no-fgvc", action="store_true", help="train only on --extra-data folders")
    p.add_argument("--train-split", default="trainval", choices=["train", "trainval"])
    p.add_argument("--val-split", default="test", choices=["val", "test"])
    p.add_argument("--extra-data", nargs="*", default=[], help="folders laid out as <dir>/{train,val}/<class>/*.jpg")
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--no-pretrained", action="store_true")
    p.add_argument("--img-size", type=int, default=320)
    p.add_argument("--epochs", type=int, default=40)
    p.add_argument("--batch-size", type=int, default=48)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--weight-decay", type=float, default=0.05)
    p.add_argument("--warmup-epochs", type=int, default=2)
    p.add_argument("--label-smoothing", type=float, default=0.1)
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--limit", type=int, default=0, help="debug: cap train/val items")
    p.add_argument("--out", default="runs/exp")
    p.add_argument("--resume", action="store_true", help="continue from <out>/last.pt")
    return p.parse_args()


def build_data(args) -> DataBundle:
    bundle = DataBundle()
    if not args.no_fgvc:
        add_fgvc(bundle, prepare_fgvc(args.data_root, workers=args.workers), args.train_split, args.val_split)
    for folder in args.extra_data:
        add_folder(bundle, folder)
    if not bundle.train:
        raise SystemExit("No training images found.")
    if args.limit:
        bundle.train, bundle.val = bundle.train[: args.limit], bundle.val[: args.limit]
    return bundle


@torch.no_grad()
def evaluate(model, loader, device, bundle: DataBundle) -> dict[str, float]:
    model.eval()
    fam = [bundle.hierarchy[c]["family"] for c in bundle.classes]
    man = [bundle.hierarchy[c]["manufacturer"] for c in bundle.classes]
    n = top1 = top5 = fam_ok = man_ok = 0
    for x, y in loader:
        x, y = x.to(device, non_blocking=True), y.to(device, non_blocking=True)
        with torch.autocast(device.type, enabled=device.type == "cuda"):
            logits = model(x)
        k = min(5, logits.shape[1])
        pred = logits.topk(k, dim=1).indices
        top1 += (pred[:, 0] == y).sum().item()
        top5 += (pred == y[:, None]).any(dim=1).sum().item()
        for p, t in zip(pred[:, 0].tolist(), y.tolist()):
            fam_ok += fam[p] == fam[t]
            man_ok += man[p] == man[t]
        n += len(y)
    n = max(n, 1)
    return {"top1": top1 / n, "top5": top5 / n, "family": fam_ok / n, "manufacturer": man_ok / n}


def main():
    sys.stdout.reconfigure(line_buffering=True)  # show progress live in Colab/Kaggle, not in big delayed chunks
    args = parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    bundle = build_data(args)
    print(f"{len(bundle.classes)} classes | {len(bundle.train)} train | {len(bundle.val)} val | device={device}")

    model = create_model(args.model, len(bundle.classes), pretrained=not args.no_pretrained).to(device)
    mean, std = norm_stats(model)
    loader_kw = dict(batch_size=args.batch_size, num_workers=args.workers, pin_memory=device.type == "cuda",
                     persistent_workers=args.workers > 0)
    train_dl = DataLoader(ItemDataset(bundle.train, train_transform(args.img_size, mean, std)),
                          shuffle=True, drop_last=len(bundle.train) > args.batch_size, **loader_kw)
    val_dl = DataLoader(ItemDataset(bundle.val, eval_transform(args.img_size, mean, std)), **loader_kw)

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    total_steps = args.epochs * len(train_dl)
    warmup = args.warmup_epochs * len(train_dl)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: (s + 1) / warmup if s < warmup else 0.5 * (1 + math.cos(math.pi * (s - warmup) / max(1, total_steps - warmup))))
    scaler = torch.amp.GradScaler(enabled=device.type == "cuda")
    loss_fn = nn.CrossEntropyLoss(label_smoothing=args.label_smoothing)

    start_epoch, history = 0, []
    if args.resume and (out / "last.pt").exists():
        ck = torch.load(out / "last.pt", map_location=device, weights_only=False)
        model.load_state_dict(ck["model"])
        opt.load_state_dict(ck["optimizer"])
        sched.load_state_dict(ck["scheduler"])
        start_epoch, history = ck["epoch"] + 1, ck["history"]
        print(f"Resumed at epoch {start_epoch}")

    for epoch in range(start_epoch, args.epochs):
        model.train()
        t0, running, seen = time.time(), 0.0, 0
        for step, (x, y) in enumerate(train_dl, 1):
            x, y = x.to(device, non_blocking=True), y.to(device, non_blocking=True)
            with torch.autocast(device.type, enabled=device.type == "cuda"):
                loss = loss_fn(model(x), y)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            running += loss.item() * len(y)
            seen += len(y)
            if step % 50 == 0:
                left = (time.time() - t0) / step * (len(train_dl) - step)
                print(f"  epoch {epoch + 1}/{args.epochs}  step {step}/{len(train_dl)}  "
                      f"loss {running / seen:.3f}  ~{left / 60:.0f} min left in this epoch")

        metrics = evaluate(model, val_dl, device, bundle) if bundle.val else {}
        history.append({"epoch": epoch, "loss": running / max(seen, 1), **metrics})
        print(f"epoch {epoch + 1}/{args.epochs}  loss {running / max(seen, 1):.3f}  "
              + "  ".join(f"{k} {v:.3f}" for k, v in metrics.items()) + f"  ({time.time() - t0:.0f}s)")

        ck = {
            "model": model.state_dict(), "optimizer": opt.state_dict(), "scheduler": sched.state_dict(),
            "epoch": epoch, "history": history,
            "meta": {"model": args.model, "img_size": args.img_size, "mean": mean, "std": std,
                     "classes": bundle.classes, "hierarchy": bundle.hierarchy},
        }
        # Write then rename, so a session killed mid-save (e.g. Colab limits) never leaves a corrupt checkpoint.
        torch.save(ck, out / "last.pt.tmp")
        os.replace(out / "last.pt.tmp", out / "last.pt")
        (out / "history.json").write_text(json.dumps(history, indent=1))

    print(f"Done. Checkpoint: {out / 'last.pt'}  ->  next: python export.py --checkpoint {out / 'last.pt'}")


if __name__ == "__main__":
    main()
