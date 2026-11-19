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

## Repository layout

```
labels.json        label -> spoken phrase
notebooks/         Kaggle notebooks (data filtering, training, TFLite export)
android/           Android app (coming)
```

## Progress log

- 2026-11-19 — Vocabulary fixed at 20 labels, verified against the Kaggle label map.
