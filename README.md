# SignBridge

Offline Android app for two-way communication between a deaf child and a hearing parent.

- **Sign → speech:** the child signs to the front camera. MediaPipe extracts hand landmarks, an on-device GRU (TFLite) classifies the sign, and Android TTS speaks a phrase.
- **Speech → sign:** the parent speaks. Vosk (offline, bundled) transcribes the word, and the app plays a video of the matching sign.

Everything runs on the phone. No internet connection is needed.

CSED404 Mobile & Ubiquitous Computing, POSTECH. Team: Muthia Nabilla Azzahra, Joachim Frydenlund.

## Vocabulary (20 signs)

All 20 labels exist in the PopSign ASL v1.0 / Kaggle `asl-signs` label set (verified against `sign_to_prediction_index_map.json`, 250 labels).

| Group | Labels |
|---|---|
| Physical needs | `hungry`, `drink`, `potty`, `sleep` |
| Pain & danger | `owie`, `sick`, `hot` |
| Basic answers | `yes`, `no`, `please`, `thankyou` |
| People & greetings | `mom`, `dad`, `hello`, `bye` |
| Actions | `go`, `wait`, `finish` |
| Feelings | `mad`, `sad` |

The dataset labels come from a child-vocabulary inventory, so some are informal (`potty`, `owie`). The model keeps the dataset labels. The app maps each label to a spoken phrase in [`labels.json`](labels.json), e.g. `potty` → "I need the toilet", `owie` → "It hurts".

### Changes from the proposal

`eat`, `help`, `more`, `allgone` and `love` are not in the 250-label Kaggle set, so they cannot be trained from PopSign. They were replaced with labels that are.

## Data

- Training: [Kaggle — Google Isolated Sign Language Recognition (`asl-signs`)](https://www.kaggle.com/competitions/asl-signs/data), MediaPipe landmarks extracted from PopSign ASL v1.0. Not stored in this repo.
- Sign reference videos: [PopSign v1.0 per-sign pages](https://signdata.cc.gatech.edu/view/datasets/popsign_v1_0/).

## Dataset facts (measured, not quoted)

Measured by `notebooks/01_filter_vocabulary.ipynb` on the Kaggle `asl-signs` release:

| | |
|---|---|
| Clips for our 20 words | 7,611 |
| Signers contributing them | **21** |
| Clips per word | 346–402 (mean 381), balanced |
| Clip length | p10 6 frames, median 25, p90 96, max 269 |
| Frames with no hand detected | 30% |
| Held-out test signers | 3 (`30680`, `37779`, `49445`) — 1,049 clips |

The PopSign ASL v1.0 *paper* describes 47 Deaf signers for the video corpus. The Kaggle landmark release we train on contains far fewer, so **the model is learned from 21 signers, not 47**. Any slide or report must say 21.

## Repository layout

```
labels.json        label -> spoken phrase
notebooks/
  01_filter_vocabulary.ipynb   vocabulary check, signer-independent split, data stats
  02_train_model.ipynb         preprocessing, GRU, held-out eval, TFLite export (v1)
  03_improve_model.ipynb       face anchors, motion features, augmentation (v2)
results/           metrics, confusion matrices, preprocessing contract, v2 TFLite model
android/           Android app (Kotlin, Jetpack Compose, CameraX, Vosk, Media3)
  app/src/main/java/edu/postech/signbridge/ml/        SignPreprocessor (Python-parity port), ParityTest
  app/src/main/java/edu/postech/signbridge/vision/    SignRecognizer interface, MediaPipeSignRecognizer
```

## Model

v1 input is 32 frames x 90 features (v2: 32 x 204, see Results). Per frame, per hand: 21 landmarks normalized to the wrist and scaled by hand size (42), the wrist position relative to the shoulder midpoint scaled by shoulder width (2), and a presence flag (1).

The body-relative term matters: wrist-only normalization erases *where* a sign is made, and `mom` (chin) and `dad` (forehead) share a handshape.

Clips are resampled to exactly 32 frames by linear index sampling, not padded or cropped — the length distribution runs from 6 to 269 frames, so a fixed crop would destroy both tails.

The GRU uses `unroll=True` so the model exports to plain TFLite builtin ops. A rolled GRU emits TensorList ops needing the Flex delegate and a much larger APK.

`preprocess_config_v2.json`, written by notebook 03, is the contract between Python and Kotlin. The two implementations must match exactly; a mismatch is silent, and shows up only as bad accuracy on the phone.

## Results

| | notebook 02 | notebook 03 |
|---|---|---|
| Features per frame | 90 | 204 |
| Anchors for hand location | shoulder midpoint | shoulder midpoint, forehead, nose, chin |
| Motion term | none | frame-to-frame deltas |
| Augmentation | none | mirror, rotate, scale, shift, time warp, frame dropout |
| Held-out signer accuracy | **74.3%** | **80.6%** |
| Top-3 accuracy | 89.3% | 93.3% |
| TFLite size | 0.83 MB | 1.14 MB |

Numbers are from the committed runs in `results/metrics.json` and `results/metrics_v2.json`.

Notebook 02's confusion matrix drove the notebook 03 design. The weak words — `owie` 45%, `go` 39%, `finish` 54%, `hot` 50%, `mad` 49%, `mom` 59% — are largely signs made near the face, and notebook 02 discarded every face landmark and located the hands against the shoulders alone. `dad` 97% was already strong in v1, which says the body-relative idea works and is worth extending.

Chance is 5%. The test set is 1,049 clips from 3 signers the model never saw.

## Pipeline

```
[1] Vocabulary chosen and verified          done
[2] Data filtered and profiled              done   notebook 01
[3] Sign-to-speech model trained            done   notebook 02 — 74.3%
[4] Accuracy improved                       done   notebook 03 — 80.6%
[5] Reference sign videos                   done   PopSign per-sign clips (not committed: *.mp4 is gitignored)
[6] Android: UI, front camera, Vosk STT, TTS, video playback   done
[7] Android: MediaPipe + TFLite recognizer  in progress  MediaPipeSignRecognizer replaces FakeSignRecognizer
[8] Python/Kotlin preprocessing parity      in progress  ParityTest
[9] On-device FPS / latency measurement     todo   logged under the SignBridgePerf tag
[10] Both directions joined, offline demo   todo
```

## Progress log

- 2026-11-19 — Vocabulary fixed at 20 labels, verified against the Kaggle label map. Dataset filtered and profiled (7,611 clips, 21 signers). Training notebook written: preprocessing, GRU, signer-independent evaluation, TFLite export.
- 2026-11-23 — Notebook 02 trained: 74.3% on held-out signers, 0.83 MB TFLite, exported model agrees with Keras on every checked clip. Confusion matrix analysed; notebook 03 written to address the face-proximity failure cluster with face anchors, motion features and augmentation: 80.6% held-out, 1.14 MB.
- 2026-11-24 — Android app (Joachim): Compose UI, CameraX front camera, Vosk offline STT, TTS, sign-video playback, `SignRecognizer` interface with a stub. Kotlin preprocessing port and MediaPipe recognizer added.

### Obstacles hit and resolved

| Problem | Root cause | Fix |
|---|---|---|
| 5 proposal words absent from the label set | the public sign list is PopSign v2.1 (~500 signs); the Kaggle landmark release is a 250-label v1.0 subset | verify every word against `sign_to_prediction_index_map.json` before building anything |
| Dataset labels are child vocabulary (`potty`, `owie`) | labels come from a child-vocabulary inventory | model keeps dataset labels; the app maps them to phrases via `labels.json` |
| `kaggle.exe` blocked by Device Guard | pip-generated launcher is unsigned | invoke the module: `python -m kaggle` |
| 40 GB download | full landmark release | train in a Kaggle notebook where the data is already mounted |
| `ArrowInvalid: No match for FieldRef.Name(landmark_idx)` | the column is `landmark_index` | corrected in the notebooks |
| TFLite export needed the Flex delegate | a rolled GRU emits TensorList ops | `unroll=True`, fixed clip length, builtin ops only |
