#!/usr/bin/env python3
"""Evaluate lightweight blow-detection rules against analysis/window_features.csv.

Run after tools/analyze_recordings.py:
    python tools/evaluate_detector_rules.py --analysis analysis

The defaults are intentionally conservative: avoid speech/ambient false positives,
then report which blow classes are missed.
"""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path

DEFAULT_POSITIVE_LABELS = {"blow-close", "blow-normal", "failed-blow"}


def main() -> None:
    parser = argparse.ArgumentParser(description="Replay candidate blow detector rules")
    parser.add_argument("--analysis", default="analysis", help="Folder with window_features.csv")
    parser.add_argument("--out", default="analysis/rule_evaluation.csv", help="Per-clip evaluation CSV")
    parser.add_argument("--positive-label", action="append", default=None, help="Label treated as expected blow")
    parser.add_argument("--min-rms", type=float, default=0.003)
    parser.add_argument("--min-zcr", type=float, default=0.18)
    parser.add_argument("--min-flatness", type=float, default=0.16)
    parser.add_argument("--min-candidate-ms", type=float, default=80.0)
    args = parser.parse_args()

    analysis = Path(args.analysis)
    positive_labels = set(args.positive_label or DEFAULT_POSITIVE_LABELS)
    clips = load_windows(analysis / "window_features.csv")
    rows = []
    for key, windows in sorted(clips.items()):
        archive, clip, label = key
        decision = evaluate_clip(
            windows,
            min_rms=args.min_rms,
            min_zcr=args.min_zcr,
            min_flatness=args.min_flatness,
            min_candidate_ms=args.min_candidate_ms,
        )
        expected = label in positive_labels
        predicted = decision["triggered"]
        if expected and predicted:
            outcome = "TP"
        elif expected and not predicted:
            outcome = "FN"
        elif not expected and predicted:
            outcome = "FP"
        else:
            outcome = "TN"
        rows.append({
            "archive": archive,
            "clip": clip,
            "label": label,
            "expected_positive": expected,
            "predicted_positive": predicted,
            "outcome": outcome,
            **decision,
        })

    write_rows(Path(args.out), rows)
    print_report(rows, positive_labels, args)


def load_windows(path: Path) -> dict[tuple[str, str, str], list[dict[str, float | str]]]:
    grouped = defaultdict(list)
    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            key = (row["archive"], row["clip"], row["label"])
            grouped[key].append({
                "start_ms": float(row["start_ms"]),
                "end_ms": float(row["end_ms"]),
                "rms": float(row["rms"]),
                "peak": float(row["peak"]),
                "zcr": float(row["zcr"]),
                "flatness": float(row["spectral_flatness"]),
                "centroid": float(row["spectral_centroid"]),
                "high_ratio": float(row["high_ratio"]),
            })
    return grouped


def evaluate_clip(
    windows: list[dict[str, float | str]],
    min_rms: float,
    min_zcr: float,
    min_flatness: float,
    min_candidate_ms: float,
) -> dict[str, float | bool | str]:
    candidate_ms = 0.0
    longest_ms = 0.0
    running_ms = 0.0
    best_rms = 0.0
    best_peak = 0.0
    best_zcr = 0.0
    best_flatness = 0.0
    best_reason = "no candidate"

    for window in windows:
        duration = float(window["end_ms"]) - float(window["start_ms"])
        rms = float(window["rms"])
        peak = float(window["peak"])
        zcr = float(window["zcr"])
        flatness = float(window["flatness"])
        high_ratio = float(window["high_ratio"])

        airflow = rms >= min_rms and zcr >= min_zcr and flatness >= min_flatness
        loud_airflow = rms >= min_rms * 2.0 and zcr >= min_zcr * 0.85 and flatness >= min_flatness * 0.65
        speech_like = zcr < 0.14 and flatness < 0.08 and high_ratio < 0.02
        candidate = (airflow or loud_airflow) and not speech_like

        if candidate:
            candidate_ms += duration
            running_ms += duration
            best_reason = "airflow" if airflow else "loud airflow"
            best_rms = max(best_rms, rms)
            best_peak = max(best_peak, peak)
            best_zcr = max(best_zcr, zcr)
            best_flatness = max(best_flatness, flatness)
        else:
            running_ms = 0.0
        longest_ms = max(longest_ms, running_ms)

    triggered = longest_ms >= min_candidate_ms
    return {
        "triggered": triggered,
        "candidate_ms": round(candidate_ms, 3),
        "longest_candidate_ms": round(longest_ms, 3),
        "best_rms": round(best_rms, 6),
        "best_peak": round(best_peak, 6),
        "best_zcr": round(best_zcr, 6),
        "best_flatness": round(best_flatness, 6),
        "reason": best_reason if triggered else "candidate too short",
    }


def write_rows(path: Path, rows: list[dict[str, object]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def print_report(rows: list[dict[str, object]], positive_labels: set[str], args) -> None:
    counts = defaultdict(int)
    by_label = defaultdict(lambda: defaultdict(int))
    for row in rows:
        counts[row["outcome"]] += 1
        by_label[row["label"]][row["outcome"]] += 1

    tp = counts["TP"]
    fp = counts["FP"]
    fn = counts["FN"]
    tn = counts["TN"]
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0

    print("Rule thresholds:")
    print(f"  positive labels: {', '.join(sorted(positive_labels))}")
    print(f"  min_rms={args.min_rms} min_zcr={args.min_zcr} min_flatness={args.min_flatness} min_candidate_ms={args.min_candidate_ms}")
    print("\nConfusion:")
    print(f"  TP={tp} FP={fp} FN={fn} TN={tn}")
    print(f"  precision={precision:.3f} recall={recall:.3f}")
    print("\nBy label:")
    for label in sorted(by_label):
        outcomes = by_label[label]
        total = sum(outcomes.values())
        print(f"  {label}: total={total} TP={outcomes['TP']} FP={outcomes['FP']} FN={outcomes['FN']} TN={outcomes['TN']}")


if __name__ == "__main__":
    main()
