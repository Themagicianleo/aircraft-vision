# Aircraft Vision

Identify an aircraft's type from a photo, entirely on the phone with no server and no internet.

```
training/   PyTorch: train → export ONNX model + labels.json → evaluate
android/    Kotlin + Jetpack Compose app using ONNX Runtime
```

The same `aircraft.onnx` + `labels.json` pair will drop into an iOS app later (ONNX Runtime has an iOS package too), so the model only has to be trained once.

## How it works

| | |
|---|---|
| Model | EfficientNet-B0 (ImageNet-pretrained, fine-tuned), 4.1M params |
| Input | 320×320 RGB. Normalisation and softmax are built into the model |
| Size on device | ~16 MB fp32, ~4–5 MB int8 |
| Speed | ~18 ms on a desktop CPU; expect a few tens of ms on a recent phone |
| Output | Top-5 types, each with family and manufacturer (e.g. `737-800 · Boeing 737 · Boeing`) |

The default dataset is [FGVC-Aircraft](https://www.robots.ox.ac.uk/~vgg/data/fgvc-aircraft/): 10,000 photos of 100 variants, 70 families and 30 manufacturers. It is for non-commercial research use.

**It dates from 2013**, so it has no 787, A350, A220, 737 MAX or E2-jets. Add those with your own photos (see below).

## 1. Train the model (free GPU on Colab)

Open `training/notebooks/train_colab.ipynb` in Colab (File → Open notebook → GitHub → `Themagicianleo/aircraft-vision`), select a T4 GPU and run all cells. At the end it downloads `aircraft_model.zip`.

Or on any machine with a GPU:

```bash
cd training
pip install -r requirements.txt
python train.py --data-root data --out runs/effb0            # downloads FGVC on first run
python export.py --checkpoint runs/effb0/last.pt --out exported \
                 --int8 --calib-dir data/fgvc_prepared/images
python evaluate.py --onnx exported/aircraft.onnx --data-root data
python predict.py exported/aircraft.onnx some_photo.jpg
```

`evaluate.py` runs the exported ONNX file with the same preprocessing as the app, so the number it prints is what the phone will get. Use it to decide between fp32 and int8.

`train.py` reports top-1 and top-5 accuracy, plus how often the family and manufacturer are right. Even when the exact variant is wrong (737-800 vs 737-900), the family is usually right.

### More photos from Wikimedia Commons (done automatically in the notebook)

`fetch_commons.py` downloads freely licensed photos from [Wikimedia Commons](https://commons.wikimedia.org). The types it fetches are listed in `commons_types.csv`:
- **types FGVC lacks:** 787-8/9/10, A350-900/1000, A220-100/300, 737 MAX 8/9, A320neo, A321neo, A330-900, 747-8, 777-300ER and E195-E2
- **common FGVC airliners:** extra, more recent photos

```bash
python fetch_commons.py --check                    # verify category names in commons_types.csv
python fetch_commons.py --out data/commons         # ~250 photos per type; rerun to resume
python train.py --data-root data --extra-data data/commons --out runs/effb0
```

It skips cockpit, interior, engine, model and crash photos. It also drops photos filed under two types, and keeps each type's category tree separate (A320 photos don't pick up A320neo ones).

Every photo's author and licence is recorded in `data/commons/CREDITS.csv`. Most are CC BY or CC BY-SA, which require crediting the author, so keep that file with the project, especially if you publish the app.

To add a type, add a row with its Commons category name (the part after `Category:` in the page URL).

### Adding your own aircraft types

```
my_photos/
  hierarchy.csv            # optional: class,family,manufacturer
  train/787-9/*.jpg
  train/A350-900/*.jpg
  val/787-9/*.jpg
  val/A350-900/*.jpg
```

```bash
python train.py --data-root data --extra-data my_photos --out runs/effb0_plus
```

New folder names become new classes. A name that matches an existing FGVC variant (e.g. `A380`) adds photos to that class. Aim for 50+ varied training photos per type. Use only photos you have the right to use.

To train only on your own photos, add `--no-fgvc`.

## 2. Build the Android app

1. Copy `aircraft.onnx` and `labels.json` into `android/app/src/main/assets/`.
2. Open the `android/` folder in [Android Studio](https://developer.android.com/studio) and press Run. On first open it installs the SDK it needs.
3. To put it on your phone: enable *Developer options → USB debugging* and plug the phone in, or build an APK (*Build → Build APK(s)*) and copy it over.

No Google Play developer account is needed to install your own APK.

The app lets you take a photo or pick one from the gallery, or share a photo to it from another app. No permissions are required.

Every push that touches `android/` is also built by GitHub Actions. The APK is attached to the run under **Actions → Android APK → Artifacts**. To ship the model in those builds, commit the two files in `assets/`.

## 3. iOS (later)

Use the `onnxruntime-objc` / `onnxruntime-c` package (CocoaPods or Swift Package Manager). Load `aircraft.onnx` and preprocess the same way: scale the short side to `img_size`, centre-crop, then RGB planar floats in [0,1]. Read the class list from `labels.json`. `AircraftClassifier.kt` is the reference to port.

## Tips for accuracy

- Photos where the aircraft fills the frame work much better than tiny aircraft in the sky. The app centre-crops a square.
- Train at a larger `--img-size` (e.g. 384), or with a larger backbone (`--model efficientnet_b2`), for more accuracy at some speed cost. `--model mobilenetv3_large_100` is lighter.
- If the model is unsure (top result below 40%), the app says so instead of guessing confidently.
