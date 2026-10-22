"""Download freely licensed aircraft photos from Wikimedia Commons for training.

    python fetch_commons.py --check                  # verify the category names in commons_types.csv
    python fetch_commons.py --out data/commons       # download (resumable; rerun to continue)
    python train.py --data-root data --extra-data data/commons --out runs/effb0_commons

Writes <out>/{train,val}/<class>/*.jpg, hierarchy.csv (read by train.py), and CREDITS.csv
with the author and licence of every photo. Most Commons licences (CC BY, CC BY-SA)
require crediting the author if you publish the photos or, arguably, a model trained on them,
so keep CREDITS.csv with the project.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import html
import io
import random
import re
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from PIL import Image

API = "https://commons.wikimedia.org/w/api.php"
# Wikimedia requires a descriptive User-Agent: https://meta.wikimedia.org/wiki/User-Agent_policy
USER_AGENT = "AircraftVision/0.1 (https://github.com/Themagicianleo/aircraft-vision; aircraft classifier training data)"
THUMB_WIDTH = 960  # one of Wikimedia's standard thumbnail sizes, so it is served from cache
MIN_SIDE = 400
STORE_SHORT_SIDE = 448

# Subcategories / file names containing these words are rarely a photo of the whole aircraft.
SKIP_WORDS = [
    "interior", "cabin", "cockpit", "flight deck", "seat", "galley", "lavatory", "lavatories", "door", "window",
    "engine", "landing gear", "wheel", "nose", "tail", "wingtip", "detail", "cargo hold",
    "model", "toy", "drawing", "diagram", "art", "painting", "stamp", "logo", "poster", "video",
    "accident", "incident", "crash", "wreck", "wreckage", "assembly", "construction", "production", "maintenance",
]


def skip_pattern(extra: list[str]) -> re.Pattern:
    # Whole words only (plurals allowed): "seat" must not match "Seattle", "art" not "Martinair".
    words = [re.escape(w.lower()) for w in SKIP_WORDS + extra if w.strip()]
    return re.compile(r"(?<![a-z])(" + "|".join(words) + r")(s|es)?(?![a-z])", re.IGNORECASE)


# ------------------------------------------------------------------------ config


def load_types(path: Path) -> list[dict]:
    lines = [l for l in path.read_text().splitlines() if l.strip() and not l.lstrip().startswith("#")]
    rows = []
    for row in csv.DictReader(lines):
        rows.append({
            "class": row["class"].strip(),
            "family": row["family"].strip(),
            "manufacturer": row["manufacturer"].strip(),
            "categories": [c.strip() for c in row["categories"].split(";") if c.strip()],
            "exclude": [w.strip() for w in (row.get("exclude") or "").split(";") if w.strip()],
        })
    return rows


# --------------------------------------------------------------------------- API


class Commons:
    def __init__(self, api: str = API):
        self.api = api
        self.http = requests.Session()
        self.http.headers["User-Agent"] = USER_AGENT

    def get(self, url: str, **kw) -> requests.Response:
        for attempt in range(6):
            try:
                r = self.http.get(url, timeout=60, **kw)
            except requests.RequestException:
                time.sleep(2 ** attempt)
                continue
            if r.status_code in (429, 500, 502, 503, 504):
                time.sleep(float(r.headers.get("Retry-After", 2 ** attempt)))
                continue
            r.raise_for_status()
            return r
        raise RuntimeError(f"Giving up on {url}")

    def query(self, **params):
        """Yields each page of results, following API continuation."""
        params = {"action": "query", "format": "json", "formatversion": "2", "maxlag": "5", **params}
        cont: dict = {}
        while True:
            data = self.get(self.api, params={**params, **cont}).json()
            if "error" in data:
                if data["error"].get("code") == "maxlag":
                    time.sleep(5)
                    continue
                raise RuntimeError(data["error"])
            yield data.get("query", {})
            if "continue" not in data:
                return
            cont = data["continue"]

    def missing_categories(self, names: list[str]) -> set[str]:
        missing = set()
        for i in range(0, len(names), 50):
            batch = names[i:i + 50]
            for q in self.query(titles="|".join("Category:" + n for n in batch), prop="categoryinfo"):
                normalized = {n["to"]: n["from"] for n in q.get("normalized", [])}
                for page in q.get("pages", []):
                    info = page.get("categoryinfo", {})
                    if not info.get("files") and not info.get("subcats"):
                        title = normalized.get(page["title"], page["title"])
                        missing.add(title.removeprefix("Category:"))
        return missing

    def suggest(self, name: str) -> list[str]:
        q = next(self.query(list="search", srsearch=name, srnamespace="14", srlimit="5"))
        return [s["title"].removeprefix("Category:") for s in q.get("search", [])]

    def members(self, category: str):
        for q in self.query(list="categorymembers", cmtitle="Category:" + category,
                            cmtype="subcat|file", cmlimit="500", cmprop="title|type"):
            yield from q.get("categorymembers", [])

    def image_info(self, titles: list[str]) -> dict[str, dict]:
        out = {}
        for i in range(0, len(titles), 50):
            for q in self.query(titles="|".join(titles[i:i + 50]), prop="imageinfo",
                                iiprop="url|size|mime|extmetadata", iiurlwidth=str(THUMB_WIDTH),
                                iiextmetadatafilter="Artist|LicenseShortName|LicenseUrl"):
                for page in q.get("pages", []):
                    if page.get("imageinfo"):
                        out[page["title"]] = page["imageinfo"][0]
        return out


# ------------------------------------------------------------------------ crawl


def crawl(api: Commons, roots: list[str], skip: re.Pattern, other_roots: set[str],
          want: int, max_depth: int, max_cats: int) -> set[str]:
    """Breadth-first walk of a category tree, collecting file titles."""
    queue = deque((r, 0) for r in roots)
    seen, files = set(), set()
    while queue and len(seen) < max_cats and len(files) < want:
        cat, depth = queue.popleft()
        if cat in seen:
            continue
        seen.add(cat)
        for m in api.members(cat):
            title = m["title"]
            if m["type"] == "subcat":
                name = title.removeprefix("Category:")
                # Don't wander into another class's tree (e.g. "Airbus A320neo" under "Airbus A320").
                if depth < max_depth and name not in other_roots and not skip.search(name):
                    queue.append((name, depth + 1))
            elif m["type"] == "file" and not skip.search(title):
                files.add(title)
    return files


def folder_name(cls: str) -> str:
    """Class names like "F-16A/B" can't be folder names; av.data.add_folder decodes this."""
    return cls.replace("/", "%2F")


def strip_html(s: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()


def split_of(title: str, val_fraction: float) -> str:
    h = int(hashlib.sha1(title.encode()).hexdigest(), 16) % 10_000
    return "val" if h < val_fraction * 10_000 else "train"


def download(api: Commons, url: str, dst: Path) -> bool:
    try:
        im = Image.open(io.BytesIO(api.get(url).content)).convert("RGB")
    except Exception as e:  # noqa: BLE001 - one bad file shouldn't stop the run
        print(f"  skip {url}: {e}")
        return False
    if min(im.size) < MIN_SIDE:
        return False
    scale = STORE_SHORT_SIDE / min(im.size)
    if scale < 1:  # keeps the cached dataset small; training crops are far smaller than this
        im = im.resize((round(im.width * scale), round(im.height * scale)), Image.BILINEAR)
    dst.parent.mkdir(parents=True, exist_ok=True)
    im.save(dst, quality=90)
    return True


# ------------------------------------------------------------------------- main


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--types", default=str(Path(__file__).with_name("commons_types.csv")))
    p.add_argument("--out", default="data/commons")
    p.add_argument("--check", action="store_true", help="only verify category names")
    p.add_argument("--only", nargs="*", help="limit to these class names")
    p.add_argument("--per-class", type=int, default=250, help="max photos per class")
    p.add_argument("--val-fraction", type=float, default=0.15)
    p.add_argument("--max-depth", type=int, default=3, help="how deep to follow subcategories")
    p.add_argument("--max-categories", type=int, default=400, help="per class, bounds API calls")
    p.add_argument("--workers", type=int, default=3, help="parallel downloads (keep small, be polite)")
    p.add_argument("--time-limit-min", type=float, default=0,
                   help="stop starting new types after this many minutes (what's downloaded is kept)")
    p.add_argument("--api", default=API, help=argparse.SUPPRESS)
    args = p.parse_args()

    api = Commons(args.api)
    types = load_types(Path(args.types))
    if args.only:
        types = [t for t in types if t["class"] in set(args.only)]

    all_cats = sorted({c for t in types for c in t["categories"]})
    try:
        missing = api.missing_categories(all_cats)
    except Exception as e:  # noqa: BLE001
        raise SystemExit(f"Could not reach Wikimedia Commons ({e}). Try again later.")
    for name in sorted(missing):
        print(f"Category not found or empty: '{name}'.  Similar: {api.suggest(name) or 'none'}")
    if args.check:
        print(f"{len(all_cats) - len(missing)}/{len(all_cats)} categories OK"
              + ("" if not missing else ". Fix the names above in commons_types.csv."))
        return

    out = Path(args.out)
    roots_by_class = {t["class"]: [c for c in t["categories"] if c not in missing] for t in types}
    all_roots = {c for cats in roots_by_class.values() for c in cats}

    # 1. Collect candidate files per class.
    candidates: dict[str, set[str]] = {}
    for t in types:
        roots = roots_by_class[t["class"]]
        if not roots:
            print(f"{t['class']}: no valid categories, skipped")
            continue
        try:
            files = crawl(api, roots, skip_pattern(t["exclude"]), all_roots - set(roots),
                          want=args.per_class * 4, max_depth=args.max_depth, max_cats=args.max_categories)
        except Exception as e:  # noqa: BLE001 - one bad type shouldn't stop the others
            print(f"{t['class']}: failed to list photos ({e}), skipped this time")
            continue
        candidates[t["class"]] = files
        print(f"{t['class']:<12} {len(files)} candidate photos")

    # 2. A photo filed under two types (e.g. an A319 next to an A320) would teach the wrong thing: drop it.
    owner: dict[str, str] = {}
    shared = set()
    for cls, files in candidates.items():
        for f in files:
            if f in owner and owner[f] != cls:
                shared.add(f)
            owner[f] = cls

    # 3. Sample, look up licence + thumbnail URL, download.
    credits_path = out / "CREDITS.csv"
    credits = {}
    if credits_path.exists():
        with open(credits_path, newline="") as f:
            credits = {r["file"]: r for r in csv.DictReader(f)}

    started = time.time()
    for t in types:
        if args.time_limit_min and time.time() - started > args.time_limit_min * 60:
            print(f"Time budget used: skipping {t['class']} and the types after it this run.")
            break
        cls = t["class"]
        if cls not in candidates:
            continue
        pool = sorted(candidates[cls] - shared)
        random.Random(cls).shuffle(pool)
        pool = pool[: args.per_class]
        try:
            info = api.image_info(pool)
        except Exception as e:  # noqa: BLE001
            print(f"{cls}: failed to look up photos ({e}), skipped this time")
            continue
        jobs = []
        for title in pool:
            ii = info.get(title)
            if not ii or ii.get("mime") not in ("image/jpeg", "image/png", "image/webp"):
                continue
            if min(ii.get("width", 0), ii.get("height", 0)) < MIN_SIDE:
                continue
            split = split_of(title, args.val_fraction)
            name = hashlib.sha1(title.encode()).hexdigest()[:16] + ".jpg"
            rel = f"{split}/{folder_name(cls)}/{name}"
            meta = ii.get("extmetadata", {})
            credits[rel] = {
                "file": rel, "class": cls, "title": title,
                "author": strip_html(meta.get("Artist", {}).get("value", "")),
                "license": strip_html(meta.get("LicenseShortName", {}).get("value", "")),
                "license_url": meta.get("LicenseUrl", {}).get("value", ""),
                "source": ii.get("descriptionurl", ""),
            }
            if not (out / rel).exists():
                jobs.append((ii.get("thumburl") or ii["url"], out / rel))
        with ThreadPoolExecutor(args.workers) as pool_ex:
            ok = list(pool_ex.map(lambda j: download(api, *j), jobs))
        for (url, dst), good in zip(jobs, ok):
            if not good:
                credits.pop(str(dst.relative_to(out)), None)
        have = sum(1 for r in credits.values() if r["class"] == cls)
        print(f"{cls:<12} {have} photos ({sum(ok)} new)")

        # Save progress after every class so an interrupted run can resume.
        out.mkdir(parents=True, exist_ok=True)
        with open(credits_path, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["file", "class", "title", "author", "license", "license_url", "source"])
            w.writeheader()
            w.writerows(sorted(credits.values(), key=lambda r: r["file"]))

    with open(out / "hierarchy.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["class", "family", "manufacturer"])
        for t in types:
            w.writerow([t["class"], t["family"], t["manufacturer"]])
    print(f"Done: {out}. Train with: python train.py --data-root data --extra-data {out}")


if __name__ == "__main__":
    main()
