#!/usr/bin/env python3
"""Analyze BlowAway recording-lab exports.

Reads exported zip files from records/, pairs WAV clips with JSON metadata,
computes lightweight windowed audio features, and writes CSV summaries that can
be used to tune the on-device heuristic detector.

Usage:
    python tools/analyze_recordings.py
    python tools/analyze_recordings.py --records records --out analysis
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import tempfile
import wave
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

WINDOW_MS = 40
HOP_MS = 40
EPSILON = 1e-12
SPECTRAL_FREQS = [125, 250, 500, 1000, 2000, 4000, 7000]
_HANN_CACHE: dict[int, list[float]] = {}


@dataclass(frozen=True)
class Clip:
    archive: str
    wav_name: str
    metadata: dict
    sample_rate: int
    samples: list[float]


@dataclass(frozen=True)
class WindowFeatures:
    archive: str
    clip: str
    label: str
    start_ms: float
    end_ms: float
    rms: float
    peak: float
    zcr: float
    spectral_centroid: float
    spectral_flatness: float
    low_ratio: float
    mid_ratio: float
    high_ratio: float
    rolloff_85: float


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze BlowAway recording-lab WAV exports")
    parser.add_argument("--records", default="records", help="Folder containing exported .zip files")
    parser.add_argument("--out", default="analysis", help="Output folder for CSV summaries")
    args = parser.parse_args()

    records_dir = Path(args.records)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    clips = list(load_clips(records_dir))
    windows = [feature for clip in clips for feature in analyze_clip(clip)]

    write_window_csv(out_dir / "window_features.csv", windows)
    clip_rows = summarize_clips(clips, windows)
    write_dict_csv(out_dir / "clip_summary.csv", clip_rows)
    label_rows = summarize_labels(clip_rows)
    write_dict_csv(out_dir / "label_summary.csv", label_rows)

    print(f"Analyzed {len(clips)} clips and {len(windows)} windows")
    print(f"Wrote {out_dir / 'window_features.csv'}")
    print(f"Wrote {out_dir / 'clip_summary.csv'}")
    print(f"Wrote {out_dir / 'label_summary.csv'}")
    print_label_report(label_rows)


def load_clips(records_dir: Path) -> Iterable[Clip]:
    seen_stems: set[str] = set()
    for archive in sorted(records_dir.glob("*.zip")):
        with tempfile.TemporaryDirectory() as temp_name:
            temp_dir = Path(temp_name)
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(temp_dir)
            metadata_by_stem = {}
            for json_path in temp_dir.rglob("*.json"):
                try:
                    metadata_by_stem[json_path.stem] = json.loads(json_path.read_text(encoding="utf-8"))
                except json.JSONDecodeError as exc:
                    print(f"Skipping invalid metadata {json_path}: {exc}")
            for wav_path in sorted(temp_dir.rglob("*.wav")):
                if wav_path.stem in seen_stems:
                    continue
                seen_stems.add(wav_path.stem)
                metadata = metadata_by_stem.get(wav_path.stem, {})
                sample_rate, samples = read_wav_mono(wav_path)
                yield Clip(
                    archive=archive.name,
                    wav_name=wav_path.name,
                    metadata=metadata,
                    sample_rate=sample_rate,
                    samples=samples,
                )


def read_wav_mono(path: Path) -> tuple[int, list[float]]:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        width = wav.getsampwidth()
        sample_rate = wav.getframerate()
        frames = wav.readframes(wav.getnframes())
    if width != 2:
        raise ValueError(f"Only 16-bit PCM WAV is supported: {path}")
    values = []
    for i in range(0, len(frames), width * channels):
        channel_values = []
        for channel in range(channels):
            offset = i + channel * width
            raw = int.from_bytes(frames[offset : offset + width], "little", signed=True)
            channel_values.append(raw / 32768.0)
        values.append(sum(channel_values) / len(channel_values))
    return sample_rate, values


def analyze_clip(clip: Clip) -> list[WindowFeatures]:
    window = max(1, int(clip.sample_rate * WINDOW_MS / 1000))
    hop = max(1, int(clip.sample_rate * HOP_MS / 1000))
    features = []
    if len(clip.samples) < window:
        return features
    label = str(clip.metadata.get("label") or infer_label(clip.wav_name))
    for start in range(0, len(clip.samples) - window + 1, hop):
        frame = clip.samples[start : start + window]
        start_ms = 1000.0 * start / clip.sample_rate
        end_ms = 1000.0 * (start + window) / clip.sample_rate
        features.append(compute_window_features(clip, label, frame, start_ms, end_ms))
    return features


def compute_window_features(
    clip: Clip,
    label: str,
    frame: list[float],
    start_ms: float,
    end_ms: float,
) -> WindowFeatures:
    rms = math.sqrt(sum(sample * sample for sample in frame) / len(frame))
    peak = max(abs(sample) for sample in frame)
    zcr = zero_crossing_rate(frame)
    bands = band_features(frame, clip.sample_rate)
    centroid = bands["centroid"]
    flatness = bands["flatness"]
    low = bands["low_ratio"]
    mid = bands["mid_ratio"]
    high = bands["high_ratio"]
    rolloff = bands["rolloff_85"]
    return WindowFeatures(
        archive=clip.archive,
        clip=clip.wav_name,
        label=label,
        start_ms=start_ms,
        end_ms=end_ms,
        rms=rms,
        peak=peak,
        zcr=zcr,
        spectral_centroid=centroid,
        spectral_flatness=flatness,
        low_ratio=low,
        mid_ratio=mid,
        high_ratio=high,
        rolloff_85=rolloff,
    )


def zero_crossing_rate(frame: list[float]) -> float:
    crossings = 0
    previous = frame[0]
    for sample in frame[1:]:
        if (previous >= 0 > sample) or (previous < 0 <= sample):
            crossings += 1
        previous = sample
    return crossings / max(1, len(frame) - 1)


def hann(index: int, count: int) -> float:
    if count <= 1:
        return 1.0
    return 0.5 - 0.5 * math.cos(2.0 * math.pi * index / (count - 1))


def band_features(frame: list[float], sample_rate: int) -> dict[str, float]:
    """Fast coarse spectral features using fixed Goertzel bins."""
    weights = hann_weights(len(frame))
    bins = [(freq, goertzel_power(frame, sample_rate, freq, weights)) for freq in SPECTRAL_FREQS if freq < sample_rate / 2]
    total = sum(power for _, power in bins) + EPSILON
    centroid = sum(freq * power for freq, power in bins) / total
    low = sum(power for freq, power in bins if freq < 600) / total
    mid = sum(power for freq, power in bins if 600 <= freq < 3000) / total
    high = sum(power for freq, power in bins if freq >= 3000) / total
    powers = [power + EPSILON for _, power in bins]
    geometric = math.exp(sum(math.log(power) for power in powers) / len(powers))
    arithmetic = sum(powers) / len(powers)
    running = 0.0
    rolloff = bins[-1][0] if bins else 0.0
    for freq, power in bins:
        running += power
        if running >= total * 0.85:
            rolloff = freq
            break
    return {
        "centroid": centroid,
        "flatness": geometric / arithmetic,
        "low_ratio": low,
        "mid_ratio": mid,
        "high_ratio": high,
        "rolloff_85": rolloff,
    }


def hann_weights(count: int) -> list[float]:
    weights = _HANN_CACHE.get(count)
    if weights is None:
        weights = [hann(i, count) for i in range(count)]
        _HANN_CACHE[count] = weights
    return weights


def goertzel_power(frame: list[float], sample_rate: int, target_freq: float, weights: list[float]) -> float:
    n = len(frame)
    k = int(0.5 + (n * target_freq / sample_rate))
    omega = 2.0 * math.pi * k / n
    coeff = 2.0 * math.cos(omega)
    q0 = 0.0
    q1 = 0.0
    q2 = 0.0
    for sample, weight in zip(frame, weights):
        q0 = coeff * q1 - q2 + sample * weight
        q2 = q1
        q1 = q0
    return q1 * q1 + q2 * q2 - coeff * q1 * q2

def summarize_clips(clips: list[Clip], windows: list[WindowFeatures]) -> list[dict[str, object]]:
    windows_by_clip = {}
    for row in windows:
        windows_by_clip.setdefault((row.archive, row.clip), []).append(row)

    rows = []
    for clip in clips:
        clip_windows = windows_by_clip.get((clip.archive, clip.wav_name), [])
        label = str(clip.metadata.get("label") or infer_label(clip.wav_name))
        duration = len(clip.samples) / clip.sample_rate if clip.sample_rate else 0.0
        rows.append({
            "archive": clip.archive,
            "clip": clip.wav_name,
            "label": label,
            "manufacturer": clip.metadata.get("manufacturer", ""),
            "model": clip.metadata.get("model", ""),
            "sdk": clip.metadata.get("sdk", ""),
            "sample_rate": clip.sample_rate,
            "duration_s": round(duration, 3),
            "window_count": len(clip_windows),
            "rms_p50": percentile([w.rms for w in clip_windows], 50),
            "rms_p90": percentile([w.rms for w in clip_windows], 90),
            "peak_p90": percentile([w.peak for w in clip_windows], 90),
            "zcr_p50": percentile([w.zcr for w in clip_windows], 50),
            "centroid_p50": percentile([w.spectral_centroid for w in clip_windows], 50),
            "flatness_p50": percentile([w.spectral_flatness for w in clip_windows], 50),
            "high_ratio_p50": percentile([w.high_ratio for w in clip_windows], 50),
            "active_ms_rms_gt_0_03": active_duration_ms(clip_windows, lambda w: w.rms > 0.03),
            "active_ms_rms_gt_0_06": active_duration_ms(clip_windows, lambda w: w.rms > 0.06),
        })
    return rows


def summarize_labels(clip_rows: list[dict[str, object]]) -> list[dict[str, object]]:
    labels = sorted({str(row["label"]) for row in clip_rows})
    rows = []
    for label in labels:
        subset = [row for row in clip_rows if row["label"] == label]
        rows.append({
            "label": label,
            "clips": len(subset),
            "duration_s_total": round(sum(float(row["duration_s"]) for row in subset), 3),
            "rms_p90_median": median_field(subset, "rms_p90"),
            "peak_p90_median": median_field(subset, "peak_p90"),
            "zcr_p50_median": median_field(subset, "zcr_p50"),
            "centroid_p50_median": median_field(subset, "centroid_p50"),
            "flatness_p50_median": median_field(subset, "flatness_p50"),
            "high_ratio_p50_median": median_field(subset, "high_ratio_p50"),
            "active_ms_rms_gt_0_03_median": median_field(subset, "active_ms_rms_gt_0_03"),
            "active_ms_rms_gt_0_06_median": median_field(subset, "active_ms_rms_gt_0_06"),
        })
    return rows

def active_duration_ms(windows: list[WindowFeatures], predicate) -> float:
    return sum((w.end_ms - w.start_ms) for w in windows if predicate(w))


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = (len(ordered) - 1) * pct / 100.0
    low = math.floor(index)
    high = math.ceil(index)
    if low == high:
        return round(ordered[int(index)], 6)
    weight = index - low
    return round(ordered[low] * (1 - weight) + ordered[high] * weight, 6)


def median_field(rows: list[dict[str, object]], field: str) -> float:
    values = [float(row[field]) for row in rows]
    return round(statistics.median(values), 6) if values else 0.0


def infer_label(name: str) -> str:
    stem = Path(name).stem
    parts = stem.split("_", 1)
    return parts[1].replace("-", " ") if len(parts) == 2 else stem


def write_window_csv(path: Path, rows: list[WindowFeatures]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(WindowFeatures.__dataclass_fields__.keys()))
        writer.writeheader()
        for row in rows:
            writer.writerow(row.__dict__)


def write_dict_csv(path: Path, rows: list[dict[str, object]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def print_label_report(rows: list[dict[str, object]]) -> None:
    if not rows:
        print("No labels found")
        return
    print("\nLabel summary:")
    for row in rows:
        print(
            f"- {row['label']}: clips={row['clips']} "
            f"rms90={row['rms_p90_median']} peak90={row['peak_p90_median']} "
            f"zcr={row['zcr_p50_median']} flat={row['flatness_p50_median']} "
            f"active>0.03={row['active_ms_rms_gt_0_03_median']}ms"
        )


if __name__ == "__main__":
    main()
