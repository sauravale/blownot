# TensorFlow Lite Training Pipeline

## Labels

The classifier output labels are:

`Blow`, `Speech`, `Cough`, `Wind`, `Fan`, `TV`, `Music`, `Keyboard`, `Traffic`, `Silence`.

Only `Blow` can trigger dismissal.

## Feature Pipeline

1. Capture 16 kHz mono PCM clips on-device or from consented datasets.
2. Segment audio into one-second windows with 50% overlap.
3. Normalize amplitude without clipping.
4. Extract log-mel spectrograms, RMS, zero crossing rate, spectral centroid, spectral flatness, and frame energy.
5. Train a compact CNN or DS-CNN model.
6. Export a float32 TensorFlow Lite model with one input tensor shaped for 16,000 normalized samples or the chosen spectrogram shape.
7. Validate on held-out users, rooms, devices, fans, traffic, TV, and phone-call audio.
8. Ship `blow_classifier.tflite` as an app asset once validation meets release thresholds.

## Release Thresholds

- Blow recall: at least 95% on held-out users.
- Non-blow false positive rate: below 0.1%.
- Speech false positive rate: 0 confirmed dismissals in validation.
- Median inference time: below 20 ms on mid-range Android devices.

## Integration

`TfliteBlowDetector` already implements the runtime inference path. The Hilt binding can be switched from `HeuristicBlowDetector` to `TfliteBlowDetector` after a validated model asset is added.
