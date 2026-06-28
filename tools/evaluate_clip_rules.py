#!/usr/bin/env python3
"""Evaluate clip-level detector thresholds from analysis/clip_summary.csv.

This is a fast tuning helper. It does not replace the Android streaming detector,
but it shows whether simple lightweight features separate labelled clips.
"""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path

DEFAULT_POSITIVE_LABELS = {"blow-close", "blow-normal", "failed-blow"}


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate clip-level blow detector thresholds")
    parser.add_argument("--analysis", default="analysis")
    parser.add_argument("--out", default="analysis/clip_rule_evaluation.csv")
    parser.add_argument("--positive-label", action="append", default=None)
    parser.add_argument("--min-rms-p90", type=float, default=0.001)
    parser.add_argument("--min-zcr-p50", type=float, default=0.26)
    parser.add_argument("--min-flatness-p50", type=float, default=0.12)
    args = parser.parse_args()

    positive_labels = set(args.positive_label or DEFAULT_POSITIVE_LABELS)
    rows = list(csv.DictReader(open(Path(args.analysis) / "clip_summary.csv", newline="", encoding="utf-8")))
    evaluated = []
    for row in rows:
        predicted = (
            float(row["rms_p90"]) >= args.min_rms_p90
            and float(row["zcr_p50"]) >= args.min_zcr_p50
            and float(row["flatness_p50"]) >= args.min_flatness_p50
        )
        expected = row["label"] in positive_labels
        if expected and predicted:
            outcome = "TP"
        elif expected and not predicted:
            outcome = "FN"
        elif not expected and predicted:
            outcome = "FP"
        else:
            outcome = "TN"
        evaluated.append({**row, "expected_positive": expected, "predicted_positive": predicted, "outcome": outcome})

    write_rows(Path(args.out), evaluated)
    print_report(evaluated, positive_labels, args)


def write_rows(path: Path, rows: list[dict[str, object]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def print_report(rows: list[dict[str, object]], positive_labels: set[str], args) -> None:
    counts = defaultdict(int)
    by_label = defaultdict(lambda: defaultdict(int))
    false_positive_clips = []
    false_negative_clips = []
    for row in rows:
        counts[row["outcome"]] += 1
        by_label[row["label"]][row["outcome"]] += 1
        if row["outcome"] == "FP":
            false_positive_clips.append(row["clip"])
        if row["outcome"] == "FN":
            false_negative_clips.append(row["clip"])

    tp = counts["TP"]
    fp = counts["FP"]
    fn = counts["FN"]
    tn = counts["TN"]
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0

    print("Clip rule thresholds:")
    print(f"  positive labels: {', '.join(sorted(positive_labels))}")
    print(f"  rms_p90>={args.min_rms_p90} zcr_p50>={args.min_zcr_p50} flatness_p50>={args.min_flatness_p50}")
    print("\nConfusion:")
    print(f"  TP={tp} FP={fp} FN={fn} TN={tn}")
    print(f"  precision={precision:.3f} recall={recall:.3f}")
    print("\nBy label:")
    for label in sorted(by_label):
        outcomes = by_label[label]
        total = sum(outcomes.values())
        print(f"  {label}: total={total} TP={outcomes['TP']} FP={outcomes['FP']} FN={outcomes['FN']} TN={outcomes['TN']}")
    if false_positive_clips:
        print("\nFalse positives:")
        for clip in false_positive_clips:
            print(f"  {clip}")
    if false_negative_clips:
        print("\nFalse negatives:")
        for clip in false_negative_clips[:20]:
            print(f"  {clip}")
        if len(false_negative_clips) > 20:
            print(f"  ... {len(false_negative_clips) - 20} more")


if __name__ == "__main__":
    main()
