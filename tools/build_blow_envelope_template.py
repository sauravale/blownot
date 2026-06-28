#!/usr/bin/env python3
"""Build duration-normalized blow loudness envelope templates from annotations."""

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

EPS = 1e-12
POINTS = 64

@dataclass(frozen=True)
class Clip:
    source: str
    wav_name: str
    label: str
    sample_rate: int
    samples: list[float]

@dataclass(frozen=True)
class Annotation:
    times: list[float]
    durations_ms: list[float]

@dataclass(frozen=True)
class Envelope:
    clip: str
    label: str
    event_index: int
    midpoint_s: float
    duration_ms: float
    peak_dbfs: float
    normalized_db: list[float]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--records", default="records")
    parser.add_argument("--annotations", default="records/blow_event_annotations.csv")
    parser.add_argument("--out", default="analysis/blow_template")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    annotations = load_annotations(Path(args.annotations))
    clips = {clip.wav_name: clip for clip in load_clips(Path(args.records))}

    envelopes: list[Envelope] = []
    for wav_name, annotation in annotations.items():
        clip = clips.get(wav_name)
        if not clip:
            print(f"Missing annotated clip: {wav_name}")
            continue
        for index, midpoint in enumerate(annotation.times):
            duration_ms = annotation.durations_ms[index] if index < len(annotation.durations_ms) else estimate_duration_ms(clip.samples, clip.sample_rate, midpoint)
            envelopes.append(extract_envelope(clip, index + 1, midpoint, duration_ms))

    write_envelopes(out_dir / "manual_blow_loudness_envelopes.csv", envelopes)
    write_template(out_dir / "manual_blow_loudness_envelope_template.csv", envelopes)
    write_similarity(out_dir / "manual_blow_loudness_envelope_similarity.csv", envelopes)
    print_report(envelopes, out_dir)


def load_annotations(path: Path) -> dict[str, Annotation]:
    annotations = {}
    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            usable = row.get("usable", "").strip().lower()
            if usable not in {"yes", "y", "true", "1"}:
                continue
            times = parse_numbers(row.get("peak_times_s", ""))
            if not times:
                continue
            durations = parse_numbers(row.get("event_durations_ms", ""))
            annotations[row["wav_name"].strip()] = Annotation(times, durations)
    return annotations


def parse_numbers(value: str) -> list[float]:
    values = []
    for part in value.replace(",", ";").split(";"):
        part = part.strip()
        if part:
            values.append(float(part))
    return values


def load_clips(records: Path):
    seen = set()
    for wav_path in sorted(records.rglob("*.wav")):
        if any(part.lower() == "new folder" for part in wav_path.parts):
            continue
        if wav_path.stem in seen:
            continue
        seen.add(wav_path.stem)
        try:
            sr, samples = read_wav(wav_path)
        except Exception as exc:
            print(f"Skipping {wav_path}: {exc}")
            continue
        meta = read_meta(wav_path.with_suffix(".json"))
        yield Clip(str(wav_path.parent), wav_path.name, meta.get("label") or infer_label(wav_path.name), sr, samples)

    for archive in sorted(records.glob("*.zip")):
        with tempfile.TemporaryDirectory() as td:
            temp = Path(td)
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(temp)
            metas = {p.stem: read_meta(p) for p in temp.rglob("*.json")}
            for wav_path in sorted(temp.rglob("*.wav")):
                if wav_path.stem in seen:
                    continue
                seen.add(wav_path.stem)
                try:
                    sr, samples = read_wav(wav_path)
                except Exception as exc:
                    print(f"Skipping {wav_path}: {exc}")
                    continue
                meta = metas.get(wav_path.stem, {})
                yield Clip(archive.name, wav_path.name, meta.get("label") or infer_label(wav_path.name), sr, samples)


def read_meta(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def infer_label(name: str) -> str:
    stem = Path(name).stem
    parts = stem.split("_", 1)
    return parts[1] if len(parts) == 2 else stem


def read_wav(path: Path):
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        width = wav.getsampwidth()
        sr = wav.getframerate()
        frames = wav.readframes(wav.getnframes())
    if width != 2:
        raise ValueError("Only 16-bit PCM WAV is supported")
    samples = []
    stride = width * channels
    for i in range(0, len(frames), stride):
        vals = []
        for ch in range(channels):
            offset = i + ch * width
            vals.append(int.from_bytes(frames[offset:offset+width], "little", signed=True) / 32768.0)
        samples.append(sum(vals) / len(vals))
    return sr, samples


def extract_envelope(clip: Clip, event_index: int, midpoint_s: float, duration_ms: float) -> Envelope:
    sr = clip.sample_rate
    duration_samples = max(1, round(duration_ms * sr / 1000.0))
    center = round(midpoint_s * sr)
    start = center - duration_samples // 2
    end = start + duration_samples
    frame = max(1, round(sr * 0.02))
    rms_points = []
    for point in range(POINTS):
        pos = start + round((end - start - frame) * point / max(1, POINTS - 1))
        chunk = [clip.samples[i] if 0 <= i < len(clip.samples) else 0.0 for i in range(pos, pos + frame)]
        rms = math.sqrt(sum(v * v for v in chunk) / len(chunk))
        rms_points.append(20 * math.log10(max(rms, EPS)))
    peak = max(rms_points)
    return Envelope(
        clip=clip.wav_name,
        label=clip.label,
        event_index=event_index,
        midpoint_s=midpoint_s,
        duration_ms=duration_ms,
        peak_dbfs=peak,
        normalized_db=[value - peak for value in rms_points],
    )


def estimate_duration_ms(samples: list[float], sr: int, midpoint_s: float) -> float:
    center = round(midpoint_s * sr)
    radius = round(0.5 * sr)
    start = max(0, center - radius)
    end = min(len(samples), center + radius)
    frame = max(1, round(sr * 0.02))
    values = []
    for pos in range(start, max(start + 1, end - frame), frame):
        chunk = samples[pos:pos + frame]
        values.append((pos, math.sqrt(sum(v * v for v in chunk) / len(chunk))))
    if not values:
        return 350.0
    peak = max(r for _, r in values)
    threshold = max(percentile(sorted(r for _, r in values), 20) * 3.0, peak * 0.18)
    active = [pos for pos, rms in values if rms >= threshold]
    if not active:
        return 350.0
    return max(120.0, min(900.0, 1000 * (max(active) - min(active) + frame) / sr))


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0
    index = (len(sorted_values) - 1) * pct / 100.0
    low = math.floor(index)
    high = math.ceil(index)
    if low == high:
        return sorted_values[int(index)]
    weight = index - low
    return sorted_values[low] * (1 - weight) + sorted_values[high] * weight


def correlation(a, b):
    ma = statistics.mean(a)
    mb = statistics.mean(b)
    da = [v - ma for v in a]
    db = [v - mb for v in b]
    denom = math.sqrt(sum(v*v for v in da) * sum(v*v for v in db))
    return 0.0 if denom <= EPS else sum(x*y for x, y in zip(da, db)) / denom


def event_name(e: Envelope):
    return f"{Path(e.clip).stem.replace('-', '_')}_event{e.event_index}_{e.midpoint_s:.3f}s"


def write_envelopes(path: Path, envelopes: list[Envelope]):
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["position_0_to_1", *[event_name(e) + "_normalized_db" for e in envelopes]])
        for i in range(POINTS):
            writer.writerow([f"{i / (POINTS - 1):.6f}", *[f"{e.normalized_db[i]:.6f}" for e in envelopes]])


def write_template(path: Path, envelopes: list[Envelope]):
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["position_0_to_1", "min_normalized_db", "p10_normalized_db", "mean_normalized_db", "p90_normalized_db", "max_normalized_db", "event_count"])
        for i in range(POINTS):
            values = sorted(e.normalized_db[i] for e in envelopes)
            writer.writerow([f"{i / (POINTS - 1):.6f}", f"{min(values):.6f}", f"{percentile(values, 10):.6f}", f"{statistics.mean(values):.6f}", f"{percentile(values, 90):.6f}", f"{max(values):.6f}", len(values)])


def write_similarity(path: Path, envelopes: list[Envelope]):
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["event_a", "event_b", "correlation", "mean_abs_delta_db"])
        for i, a in enumerate(envelopes):
            for b in envelopes[i+1:]:
                writer.writerow([event_name(a), event_name(b), f"{correlation(a.normalized_db, b.normalized_db):.6f}", f"{sum(abs(x-y) for x,y in zip(a.normalized_db,b.normalized_db))/POINTS:.6f}"])


def print_report(envelopes: list[Envelope], out_dir: Path):
    print(f"Extracted {len(envelopes)} annotated blow envelopes")
    if len(envelopes) > 1:
        corrs = []
        for i, a in enumerate(envelopes):
            for b in envelopes[i+1:]:
                corrs.append(correlation(a.normalized_db, b.normalized_db))
        print(f"Envelope correlation median={statistics.median(corrs):.3f}, p10={percentile(sorted(corrs), 10):.3f}, p90={percentile(sorted(corrs), 90):.3f}")
    print(f"Wrote {out_dir / 'manual_blow_loudness_envelopes.csv'}")
    print(f"Wrote {out_dir / 'manual_blow_loudness_envelope_template.csv'}")
    print(f"Wrote {out_dir / 'manual_blow_loudness_envelope_similarity.csv'}")


if __name__ == "__main__":
    main()
