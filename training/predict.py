"""Identify the aircraft in one or more photos with an exported model.

    python predict.py exported/aircraft.onnx photo1.jpg photo2.jpg
"""

import argparse

from av.mobile import OnnxClassifier


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("onnx")
    p.add_argument("images", nargs="+")
    p.add_argument("--top", type=int, default=5)
    args = p.parse_args()

    clf = OnnxClassifier(args.onnx)
    for path in args.images:
        print(path)
        for r in clf.top_k(path, args.top):
            print(f"  {r['confidence']:6.1%}  {r['name']:<20} {r['family']} / {r['manufacturer']}")


if __name__ == "__main__":
    main()
