# Recording Analysis

Use the recording lab exports to tune BlowAway's lightweight detector without adding an ML dependency.

## Generate summaries

```powershell
python tools/analyze_recordings.py --records records --out analysis
python tools/evaluate_clip_rules.py --analysis analysis
python tools/evaluate_detector_rules.py --analysis analysis
```

The analyser reads every `*.zip` in `records/`, extracts WAV and JSON pairs, deduplicates clips by WAV filename/stem, and writes:

- `analysis/window_features.csv`: one row per 40 ms analysis window.
- `analysis/clip_summary.csv`: one row per unique WAV clip.
- `analysis/label_summary.csv`: aggregate ranges per label.
- `analysis/clip_rule_evaluation.csv`: clip-level threshold evaluation.
- `analysis/rule_evaluation.csv`: streaming/window-rule evaluation.

The analyser uses only Python's standard library. It computes time-domain features plus coarse fixed-band spectral features using Goertzel bins, which keeps it close to what the Android app can run cheaply.

## Current dataset

Latest run:

- 76 unique clips.
- 14,883 analysis windows.
- Labels: ambient noisy, ambient quiet, blow close, blow far, blow normal, failed blow, speech.

`failed-blow` currently means sustained/continuous blow in the captured dataset. Treat it as a long-blow class unless it is renamed in future captures.

## Current observations

- Speech has higher volume than many normal blows, but much lower ZCR and spectral flatness.
- Normal/close/long blows generally have higher ZCR and higher spectral flatness.
- Ambient noisy can overlap with airflow in ZCR/flatness, so a noise/volume gate is still needed.
- Blow far is close to ambient in this dataset and may be better treated as an optional sensitivity target rather than a default-positive class.

## Current best simple clip rule

`tools/evaluate_clip_rules.py` currently defaults to:

- `rms_p90 >= 0.001`
- `zcr_p50 >= 0.26`
- `flatness_p50 >= 0.12`
- positive labels: `blow-close`, `blow-normal`, `failed-blow`

Current result:

- TP: 28
- FP: 2
- FN: 23
- TN: 23
- Precision: 0.933
- Recall: 0.549
- Speech false positives: 0

The two false positives are both `ambient-noisy`, so the next tuning step should focus on rejecting those without losing too many normal blows.

## Next detector candidates

A lightweight streaming detector should probably use:

- Adaptive noise floor instead of absolute RMS only.
- Segment duration above noise floor.
- High ZCR plus high flatness as airflow evidence.
- Speech rejection using low flatness / low ZCR / voiced envelope patterns.
- A confidence score that combines RMS-over-floor, duration, ZCR, and flatness.
- Optional sensitivity modes where `blow-far` is only targeted by high sensitivity.

## Medium-light trained detector candidate

Run:

```powershell
python tools/train_light_detector.py --analysis analysis --max-depth 2 --min-leaf 4
```

The trainer builds examples from the loudest/most active windows in each clip instead of whole-clip medians. That better approximates the streaming detector's candidate segment behaviour.

Current depth-2 tree candidate:

```kotlin
fun isBlow(features: SegmentFeatures): Boolean {
    if (features.activeRatioRmsGt0001 < 0.518282f) {
        if (features.activeRatioRmsGt0003 < 0.012884f) {
            return false
        } else {
            return true
        }
    } else {
        if (features.rmsDynamicRange < 0.090447f) {
            return false
        } else {
            return true
        }
    }
}
```

Current leave-one-clip-out estimate:

- TP: 46
- FP: 4
- FN: 5
- TN: 18
- Precision: 0.920
- Recall: 0.902
- Speech false positives: 2
- Ambient false positives: 2

This is a much stronger candidate than the hand threshold rule, but it should be validated with more phones and real-world noise before being treated as universal.
