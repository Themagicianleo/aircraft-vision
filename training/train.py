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
from timm.utils import ModelEmaV3
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
    p.add_argument("--threads", type=int, default=0,
                   help="CPU threads for the model (0 = all); leave one free on shared CI machines")
    p.add_argument("--limit", type=int, default=0, help="debug: cap train/val items")
    p.add_argument("--out", default="runs/exp")
    p.add_argument("--resume", action="store_true", help="continue from <out>/last.pt")
    p.add_argument("--time-limit-min", type=float, default=0,
                   help="stop cleanly (checkpoint saved) before this many minutes; rerun with --resume to continue")
    p.add_argument("--eval-every", type=int, default=1, help="evaluate every N epochs (always on the last)")
    p.add_argument("--ema-decay", type=float, default=0.9998,
                   help="keep an exponential moving average of the weights (evaluated and exported); 0 = off")
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
    if args.threads:
        torch.set_num_threads(args.threads)

    bundle = build_data(args)
    print(f"{len(bundle.classes)} classes | {len(bundle.train)} train | {len(bundle.val)} val | device={device}")

    model = create_model(args.model, len(bundle.classes), pretrained=not args.no_pretrained).to(device)
    mean, std = norm_stats(model)
    loader_kw = dict(batch_size=args.batch_size, num_workers=args.workers, pin_memory=device.type == "cuda")
    train_dl = DataLoader(ItemDataset(bundle.train, train_transform(args.img_size, mean, std)),
                          shuffle=True, drop_last=len(bundle.train) > args.batch_size,
                          persistent_workers=args.workers > 0, **loader_kw)
    # Not persistent: evaluation is occasional, and idle extra worker processes cost memory.
    val_dl = DataLoader(ItemDataset(bundle.val, eval_transform(args.img_size, mean, std)), **loader_kw)

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    total_steps = args.epochs * len(train_dl)
    warmup = args.warmup_epochs * len(train_dl)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: (s + 1) / warmup if s < warmup else 0.5 * (1 + math.cos(math.pi * (s - warmup) / max(1, total_steps - warmup))))
    scaler = torch.amp.GradScaler(enabled=device.type == "cuda")
    # Averaged weights are smoother than the last step's and usually a little more accurate.
    ema = ModelEmaV3(model, decay=args.ema_decay, use_warmup=True) if args.ema_decay else None
    loss_fn = nn.CrossEntropyLoss(label_smoothing=args.label_smoothing)

    start_epoch, history = 0, []
    if args.resume and (out / "last.pt").exists():
        ck = torch.load(out / "last.pt", map_location=device, weights_only=False)
        model.load_state_dict(ck.get("train_model", ck["model"]))
        if ema is not None:
            ema.module.load_state_dict(ck["model"])  # the averaged weights (or, for old checkpoints, the plain ones)
        opt.load_state_dict(ck["optimizer"])
        sched.load_state_dict(ck["scheduler"])
        start_epoch, history = ck["epoch"] + 1, ck["history"]
        print(f"Resumed at epoch {start_epoch}")

    started, slowest_epoch = time.time(), 0.0
    for epoch in range(start_epoch, args.epochs):
        if args.time_limit_min and time.time() - started + slowest_epoch > args.time_limit_min * 60:
            print(f"Time budget used: stopping after {epoch} of {args.epochs} epochs. Rerun with --resume to continue.")
            break
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
            if ema is not None:
                ema.update(model, step=sched.last_epoch)
            running += loss.item() * len(y)
            seen += len(y)
            if step % 50 == 0:
                left = (time.time() - t0) / step * (len(train_dl) - step)
                print(f"  epoch {epoch + 1}/{args.epochs}  step {step}/{len(train_dl)}  "
                      f"loss {running / seen:.3f}  ~{left / 60:.0f} min left in this epoch")

        last = epoch == args.epochs - 1
        best_weights = ema.module if ema is not None else model
        metrics = evaluate(best_weights, val_dl, device, bundle) if bundle.val and (last or (epoch + 1) % args.eval_every == 0) else {}
        history.append({"epoch": epoch, "loss": running / max(seen, 1), **metrics})
        print(f"epoch {epoch + 1}/{args.epochs}  loss {running / max(seen, 1):.3f}  "
              + "  ".join(f"{k} {v:.3f}" for k, v in metrics.items()) + f"  ({time.time() - t0:.0f}s)")

        ck = {
            # "model" holds the weights to export (the averaged ones when EMA is on).
            "model": best_weights.state_dict(), "optimizer": opt.state_dict(), "scheduler": sched.state_dict(),
            "epoch": epoch, "history": history,
            **({"train_model": model.state_dict()} if ema is not None else {}),
            "meta": {"model": args.model, "img_size": args.img_size, "mean": mean, "std": std,
                     "classes": bundle.classes, "hierarchy": bundle.hierarchy},
        }
        # Write then rename, so a session killed mid-save (e.g. Colab limits) never leaves a corrupt checkpoint.
        torch.save(ck, out / "last.pt.tmp")
        os.replace(out / "last.pt.tmp", out / "last.pt")
        (out / "history.json").write_text(json.dumps(history, indent=1))
        slowest_epoch = max(slowest_epoch, time.time() - t0)

    done = len(history) >= args.epochs
    (out / "status.json").write_text(json.dumps({
        "epochs_done": len(history), "epochs_total": args.epochs, "finished": done,
        "last_metrics": next((h for h in reversed(history) if "top1" in h), None)}, indent=1))
    if not done:
        return
    print(f"Done. Checkpoint: {out / 'last.pt'}  ->  next: python export.py --checkpoint {out / 'last.pt'}")


if __name__ == "__main__":
    main()
