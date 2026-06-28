#!/usr/bin/env python3
"""Train lightweight detector candidates from BlowAway recording analysis.

This script intentionally avoids heavy runtime assumptions. Training can be slow,
but the emitted model is a tiny decision tree or linear score that can be ported
into Android as plain Kotlin.

Run:
    python tools/train_light_detector.py --analysis analysis
"""

from __future__ import annotations

import argparse
import csv
import math
import statistics
from dataclasses import dataclass
from pathlib import Path

DEFAULT_POSITIVE_LABELS = {"blow-close", "blow-normal", "failed-blow"}
DEFAULT_EXCLUDED_LABELS = {"blow-far"}

FEATURES = [
    "rms_top_p50",
    "rms_top_p90",
    "peak_top_p90",
    "zcr_top_p50",
    "centroid_top_p50",
    "flatness_top_p50",
    "high_ratio_top_p50",
    "rms_dynamic_range",
    "active_ratio_rms_gt_0_001",
    "active_ratio_rms_gt_0_003",
]


@dataclass(frozen=True)
class Example:
    clip: str
    label: str
    y: int
    features: dict[str, float]


@dataclass(frozen=True)
class Stump:
    feature: str
    threshold: float
    greater_is_positive: bool
    score: float


@dataclass(frozen=True)
class TreeNode:
    prediction: int
    feature: str | None = None
    threshold: float = 0.0
    left: "TreeNode | None" = None
    right: "TreeNode | None" = None

    @property
    def is_leaf(self) -> bool:
        return self.feature is None


def main() -> None:
    parser = argparse.ArgumentParser(description="Train tiny lightweight detector candidates")
    parser.add_argument("--analysis", default="analysis")
    parser.add_argument("--positive-label", action="append", default=None)
    parser.add_argument("--exclude-label", action="append", default=None)
    parser.add_argument("--max-depth", type=int, default=3)
    parser.add_argument("--min-leaf", type=int, default=3)
    args = parser.parse_args()

    positives = set(args.positive_label or DEFAULT_POSITIVE_LABELS)
    excluded = set(args.exclude_label or DEFAULT_EXCLUDED_LABELS)
    examples = load_examples(Path(args.analysis) / "window_features.csv", positives, excluded)
    if not examples:
        raise SystemExit("No training examples found. Run tools/analyze_recordings.py first.")

    print(f"Training examples: {len(examples)}")
    print(f"Positive labels: {', '.join(sorted(positives))}")
    print(f"Excluded labels: {', '.join(sorted(excluded)) if excluded else 'none'}")
    print_label_counts(examples)

    tree = build_tree(examples, max_depth=args.max_depth, min_leaf=args.min_leaf)
    train_metrics = evaluate_tree(tree, examples)
    loo_metrics = leave_one_out_tree(examples, max_depth=args.max_depth, min_leaf=args.min_leaf)

    print("\nDecision tree, training set:")
    print_metrics(train_metrics)
    print("\nDecision tree, leave-one-clip-out:")
    print_metrics(loo_metrics)
    print("\nKotlin-friendly tree:")
    print_kotlin_tree(tree)

    stumps = best_stumps(examples)[:8]
    print("\nBest one-rule splits:")
    for stump in stumps:
        direction = ">=" if stump.greater_is_positive else "<"
        metrics = evaluate_stump(stump, examples)
        print(
            f"  {stump.feature} {direction} {stump.threshold:.6f}: "
            f"precision={metrics['precision']:.3f} recall={metrics['recall']:.3f} "
            f"FP={metrics['fp']} FN={metrics['fn']}"
        )

    weights = train_linear_score(examples)
    linear_metrics = evaluate_linear(weights, examples)
    print("\nLinear score, training set:")
    print_metrics(linear_metrics)
    print("\nKotlin-friendly linear score:")
    print_linear(weights)


def load_examples(path: Path, positives: set[str], excluded: set[str]) -> list[Example]:
    grouped: dict[tuple[str, str], list[dict[str, float | str]]] = {}
    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            label = row["label"]
            if label in excluded:
                continue
            key = (row["clip"], label)
            grouped.setdefault(key, []).append(row)

    examples: list[Example] = []
    for (clip, label), rows in grouped.items():
        features = active_window_features(rows)
        examples.append(Example(clip, label, 1 if label in positives else 0, features))
    return examples


def active_window_features(rows: list[dict[str, str]]) -> dict[str, float]:
    parsed = [
        {
            "rms": float(row["rms"]),
            "peak": float(row["peak"]),
            "zcr": float(row["zcr"]),
            "centroid": float(row["spectral_centroid"]),
            "flatness": float(row["spectral_flatness"]),
            "high_ratio": float(row["high_ratio"]),
        }
        for row in rows
    ]
    by_rms = sorted(parsed, key=lambda row: row["rms"], reverse=True)
    top_count = max(3, min(len(by_rms), math.ceil(len(by_rms) * 0.15)))
    top = by_rms[:top_count]
    all_rms = [row["rms"] for row in parsed]
    return {
        "rms_top_p50": percentile([row["rms"] for row in top], 50),
        "rms_top_p90": percentile([row["rms"] for row in top], 90),
        "peak_top_p90": percentile([row["peak"] for row in top], 90),
        "zcr_top_p50": percentile([row["zcr"] for row in top], 50),
        "centroid_top_p50": percentile([row["centroid"] for row in top], 50),
        "flatness_top_p50": percentile([row["flatness"] for row in top], 50),
        "high_ratio_top_p50": percentile([row["high_ratio"] for row in top], 50),
        "rms_dynamic_range": max(all_rms) - percentile(all_rms, 20),
        "active_ratio_rms_gt_0_001": sum(1 for row in parsed if row["rms"] > 0.001) / len(parsed),
        "active_ratio_rms_gt_0_003": sum(1 for row in parsed if row["rms"] > 0.003) / len(parsed),
    }

def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = (len(ordered) - 1) * pct / 100.0
    low = math.floor(index)
    high = math.ceil(index)
    if low == high:
        return ordered[int(index)]
    weight = index - low
    return ordered[low] * (1 - weight) + ordered[high] * weight


def print_label_counts(examples: list[Example]) -> None:
    counts = {}
    for example in examples:
        counts[example.label] = counts.get(example.label, 0) + 1
    print("Labels:")
    for label in sorted(counts):
        print(f"  {label}: {counts[label]}")


def build_tree(examples: list[Example], max_depth: int, min_leaf: int) -> TreeNode:
    prediction = majority(examples)
    if max_depth == 0 or is_pure(examples) or len(examples) < min_leaf * 2:
        return TreeNode(prediction=prediction)

    split = best_split(examples, min_leaf)
    if split is None:
        return TreeNode(prediction=prediction)

    feature, threshold, impurity = split
    left_examples = [ex for ex in examples if ex.features[feature] < threshold]
    right_examples = [ex for ex in examples if ex.features[feature] >= threshold]
    if not left_examples or not right_examples:
        return TreeNode(prediction=prediction)

    return TreeNode(
        prediction=prediction,
        feature=feature,
        threshold=threshold,
        left=build_tree(left_examples, max_depth - 1, min_leaf),
        right=build_tree(right_examples, max_depth - 1, min_leaf),
    )


def best_split(examples: list[Example], min_leaf: int) -> tuple[str, float, float] | None:
    best: tuple[str, float, float] | None = None
    for feature in FEATURES:
        values = sorted(set(ex.features[feature] for ex in examples))
        thresholds = [(a + b) / 2.0 for a, b in zip(values, values[1:])]
        for threshold in thresholds:
            left = [ex for ex in examples if ex.features[feature] < threshold]
            right = [ex for ex in examples if ex.features[feature] >= threshold]
            if len(left) < min_leaf or len(right) < min_leaf:
                continue
            impurity = weighted_gini(left, right)
            if best is None or impurity < best[2]:
                best = (feature, threshold, impurity)
    return best


def weighted_gini(left: list[Example], right: list[Example]) -> float:
    total = len(left) + len(right)
    return (len(left) / total) * gini(left) + (len(right) / total) * gini(right)


def gini(examples: list[Example]) -> float:
    if not examples:
        return 0.0
    p = sum(ex.y for ex in examples) / len(examples)
    return 1.0 - p * p - (1.0 - p) * (1.0 - p)


def is_pure(examples: list[Example]) -> bool:
    return len({ex.y for ex in examples}) == 1


def majority(examples: list[Example]) -> int:
    return 1 if sum(ex.y for ex in examples) >= len(examples) / 2 else 0


def predict_tree(tree: TreeNode, example: Example) -> int:
    node = tree
    while not node.is_leaf:
        assert node.feature is not None and node.left is not None and node.right is not None
        node = node.left if example.features[node.feature] < node.threshold else node.right
    return node.prediction


def evaluate_tree(tree: TreeNode, examples: list[Example]) -> dict[str, float | int]:
    return metrics([(ex.y, predict_tree(tree, ex), ex.label, ex.clip) for ex in examples])


def leave_one_out_tree(examples: list[Example], max_depth: int, min_leaf: int) -> dict[str, float | int]:
    results = []
    for index, example in enumerate(examples):
        train = examples[:index] + examples[index + 1 :]
        tree = build_tree(train, max_depth=max_depth, min_leaf=min_leaf)
        results.append((example.y, predict_tree(tree, example), example.label, example.clip))
    return metrics(results)


def metrics(results: list[tuple[int, int, str, str]]) -> dict[str, float | int]:
    tp = sum(1 for y, p, _, _ in results if y == 1 and p == 1)
    fp = sum(1 for y, p, _, _ in results if y == 0 and p == 1)
    fn = sum(1 for y, p, _, _ in results if y == 1 and p == 0)
    tn = sum(1 for y, p, _, _ in results if y == 0 and p == 0)
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "tn": tn,
        "precision": tp / (tp + fp) if tp + fp else 0.0,
        "recall": tp / (tp + fn) if tp + fn else 0.0,
        "accuracy": (tp + tn) / len(results) if results else 0.0,
        "speech_fp": sum(1 for y, p, label, _ in results if label == "speech" and p == 1),
        "ambient_fp": sum(1 for y, p, label, _ in results if label.startswith("ambient") and p == 1),
    }


def print_metrics(result: dict[str, float | int]) -> None:
    print(
        f"  TP={result['tp']} FP={result['fp']} FN={result['fn']} TN={result['tn']} "
        f"precision={result['precision']:.3f} recall={result['recall']:.3f} "
        f"accuracy={result['accuracy']:.3f} speechFP={result['speech_fp']} ambientFP={result['ambient_fp']}"
    )


def best_stumps(examples: list[Example]) -> list[Stump]:
    stumps = []
    for feature in FEATURES:
        values = sorted(set(ex.features[feature] for ex in examples))
        thresholds = [(a + b) / 2.0 for a, b in zip(values, values[1:])]
        for threshold in thresholds:
            for greater in (True, False):
                pred = [(1 if (ex.features[feature] >= threshold) == greater else 0) for ex in examples]
                score = balanced_score(examples, pred)
                stumps.append(Stump(feature, threshold, greater, score))
    return sorted(stumps, key=lambda stump: stump.score, reverse=True)


def evaluate_stump(stump: Stump, examples: list[Example]) -> dict[str, float | int]:
    results = []
    for ex in examples:
        pred = 1 if (ex.features[stump.feature] >= stump.threshold) == stump.greater_is_positive else 0
        results.append((ex.y, pred, ex.label, ex.clip))
    return metrics(results)


def balanced_score(examples: list[Example], predictions: list[int]) -> float:
    result = metrics([(ex.y, pred, ex.label, ex.clip) for ex, pred in zip(examples, predictions)])
    return float(result["recall"]) + float(result["precision"]) - 0.8 * int(result["speech_fp"]) - 0.2 * int(result["ambient_fp"])


def train_linear_score(examples: list[Example]) -> dict[str, float]:
    # Fisher-style normalized class separation. Runtime is just weighted addition.
    positives = [ex for ex in examples if ex.y == 1]
    negatives = [ex for ex in examples if ex.y == 0]
    weights: dict[str, float] = {}
    for feature in FEATURES:
        pos_values = [ex.features[feature] for ex in positives]
        neg_values = [ex.features[feature] for ex in negatives]
        pos_mean = statistics.mean(pos_values)
        neg_mean = statistics.mean(neg_values)
        spread = statistics.pstdev(pos_values + neg_values) or 1.0
        weights[feature] = (pos_mean - neg_mean) / spread
    scores = [linear_raw_score(weights, ex) for ex in examples]
    thresholds = sorted(set(scores))
    best_threshold = 0.0
    best_score = -999.0
    for threshold in thresholds:
        predictions = [1 if score >= threshold else 0 for score in scores]
        score = balanced_score(examples, predictions)
        if score > best_score:
            best_score = score
            best_threshold = threshold
    weights["threshold"] = best_threshold
    return weights


def linear_raw_score(weights: dict[str, float], example: Example) -> float:
    return sum(weights[feature] * example.features[feature] for feature in FEATURES)


def evaluate_linear(weights: dict[str, float], examples: list[Example]) -> dict[str, float | int]:
    threshold = weights["threshold"]
    results = []
    for ex in examples:
        pred = 1 if linear_raw_score(weights, ex) >= threshold else 0
        results.append((ex.y, pred, ex.label, ex.clip))
    return metrics(results)


def print_kotlin_tree(tree: TreeNode) -> None:
    print("fun isBlow(features: SegmentFeatures): Boolean {")
    print_tree_node(tree, indent="    ")
    print("}")


def print_tree_node(node: TreeNode, indent: str) -> None:
    if node.is_leaf:
        print(f"{indent}return {str(node.prediction == 1).lower()}")
        return
    assert node.feature is not None and node.left is not None and node.right is not None
    kotlin_feature = kotlin_name(node.feature)
    print(f"{indent}if (features.{kotlin_feature} < {node.threshold:.6f}f) {{")
    print_tree_node(node.left, indent + "    ")
    print(f"{indent}}} else {{")
    print_tree_node(node.right, indent + "    ")
    print(f"{indent}}}")


def print_linear(weights: dict[str, float]) -> None:
    print("fun blowScore(features: SegmentFeatures): Float {")
    terms = [f"        {weights[feature]:.6f}f * features.{kotlin_name(feature)}" for feature in FEATURES]
    print("    return\n" + " +\n".join(terms))
    print("}")
    print(f"// Trigger when blowScore(features) >= {weights['threshold']:.6f}f")


def kotlin_name(feature: str) -> str:
    parts = feature.split("_")
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


if __name__ == "__main__":
    main()
