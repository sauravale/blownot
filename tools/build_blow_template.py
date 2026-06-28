#!/usr/bin/env python3
"""Build log-frequency blow spectral templates from recording-lab WAV files.

Outputs:
- specific three-blow comparison for 1782605969288_blow-normal.wav
- all detected blow-event spectra from positive blow clips
- min/max/mean log-frequency curve model

The spectra are normalized by subtracting each event's own max dB, so the curve
shape can be compared independent of amplitude.
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

EPS = 1e-12
DEFAULT_BLOW_LABELS = {"blow-normal", "blow-close", "failed-blow"}
DEFAULT_SPECIFIC_TIMES = [2.10, 3.56, 4.75]


@dataclass(frozen=True)
class Clip:
    source: str
    wav_name: str
    label: str
    sample_rate: int
    samples: list[float]


@dataclass(frozen=True)
class EventSpectrum:
    source: str
    clip: str
    label: str
    event_index: int
    peak_time_s: float
    duration_ms: float
    raw_peak: float
    values_db: list[float]
    normalized_db: list[float]


def main() -> None:
    parser = argparse.ArgumentParser(description="Build BlowAway log-frequency blow templates")
    parser.add_argument("--records", default="records")
    parser.add_argument("--out", default="analysis/blow_template")
    parser.add_argument("--bands", type=int, default=48)
    parser.add_argument("--min-frequency", type=float, default=60.0)
    parser.add_argument("--max-frequency", type=float, default=7600.0)
    parser.add_argument("--window-ms", type=float, default=128.0)
    parser.add_argument("--annotations", default="records/blow_event_annotations.csv")
    args = parser.parse_args()

    records = Path(args.records)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    centers = log_frequency_centers(args.min_frequency, args.max_frequency, args.bands)

    clips = list(load_clips(records))
    annotations = load_annotations(Path(args.annotations))
    specific = find_clip(clips, "1782605969288_blow-normal.wav")
    if specific:
        specific_times = annotations.get(specific.wav_name) or DEFAULT_SPECIFIC_TIMES
        specific_events = extract_specific_events(specific, specific_times, centers, args.window_ms)
        write_event_csv(out_dir / "1782605969288_three_blow_log_spectra.csv", centers, specific_events)
        write_similarity_csv(out_dir / "1782605969288_three_blow_similarity.csv", specific_events)
        print_specific_report(specific_events)
    else:
        print("Specific clip 1782605969288_blow-normal.wav not found")

    blow_clips = [clip for clip in clips if clip.label in DEFAULT_BLOW_LABELS]
    manual_events: list[EventSpectrum] = []
    automatic_events: list[EventSpectrum] = []
    for clip in blow_clips:
        if clip.wav_name in annotations:
            manual_events.extend(extract_specific_events(clip, annotations[clip.wav_name], centers, args.window_ms))
        else:
            automatic_events.extend(extract_detected_events(clip, centers, args.window_ms))
    all_events = manual_events + automatic_events

    write_event_csv(out_dir / "manual_blow_event_log_spectra.csv", centers, manual_events)
    write_template_csv(out_dir / "manual_blow_log_frequency_template_min_max.csv", centers, manual_events)
    write_event_csv(out_dir / "all_blow_event_log_spectra.csv", centers, all_events)
    write_template_csv(out_dir / "blow_log_frequency_template_min_max.csv", centers, all_events)
    write_similarity_csv(out_dir / "all_blow_event_similarity.csv", all_events)
    print_all_report(all_events, centers, out_dir)




def is_ignored_record_path(path: Path) -> bool:
    return any(part.lower() == "new folder" for part in path.parts)

def load_annotations(path: Path) -> dict[str, list[float]]:
    if not path.exists():
        return {}
    annotations: dict[str, list[float]] = {}
    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            usable = row.get("usable", "").strip().lower()
            times = row.get("peak_times_s", "").strip()
            wav_name = row.get("wav_name", "").strip()
            if usable not in {"yes", "y", "true", "1"} or not times or not wav_name:
                continue
            parsed = []
            for part in times.replace(",", ";").split(";"):
                part = part.strip()
                if part:
                    parsed.append(float(part))
            if parsed:
                annotations[wav_name] = parsed
    return annotations


def load_clips(records: Path) -> Iterable[Clip]:
    seen_stems: set[str] = set()
    for wav_path in sorted(records.rglob("*.wav")):
        if is_ignored_record_path(wav_path):
            continue
        if wav_path.stem in seen_stems:
            continue
        seen_stems.add(wav_path.stem)
        metadata = read_metadata(wav_path.with_suffix(".json"))
        try:
            sample_rate, samples = read_wav(wav_path)
        except (FileNotFoundError, wave.Error, ValueError) as exc:
            print(f"Skipping {wav_path}: {exc}")
            continue
        yield Clip(str(wav_path.parent), wav_path.name, metadata.get("label") or infer_label(wav_path.name), sample_rate, samples)

    for archive in sorted(records.glob("*.zip")):
        with tempfile.TemporaryDirectory() as temp_name:
            temp_dir = Path(temp_name)
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(temp_dir)
            metadata_by_stem = {path.stem: read_metadata(path) for path in temp_dir.rglob("*.json")}
            for wav_path in sorted(temp_dir.rglob("*.wav")):
                if is_ignored_record_path(wav_path):
                    continue
                if wav_path.stem in seen_stems:
                    continue
                seen_stems.add(wav_path.stem)
                metadata = metadata_by_stem.get(wav_path.stem, {})
                try:
                    sample_rate, samples = read_wav(wav_path)
                except (FileNotFoundError, wave.Error, ValueError) as exc:
                    print(f"Skipping {wav_path}: {exc}")
                    continue
                yield Clip(archive.name, wav_path.name, metadata.get("label") or infer_label(wav_path.name), sample_rate, samples)


def read_metadata(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def read_wav(path: Path) -> tuple[int, list[float]]:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        width = wav.getsampwidth()
        sample_rate = wav.getframerate()
        frames = wav.readframes(wav.getnframes())
    if width != 2:
        raise ValueError(f"Only 16-bit PCM WAV is supported: {path}")
    samples = []
    stride = width * channels
    for i in range(0, len(frames), stride):
        vals = []
        for ch in range(channels):
            offset = i + ch * width
            vals.append(int.from_bytes(frames[offset:offset + width], "little", signed=True) / 32768.0)
        samples.append(sum(vals) / len(vals))
    return sample_rate, samples


def infer_label(name: str) -> str:
    stem = Path(name).stem
    parts = stem.split("_", 1)
    return parts[1] if len(parts) == 2 else stem


def find_clip(clips: list[Clip], wav_name: str) -> Clip | None:
    return next((clip for clip in clips if clip.wav_name == wav_name), None)


def log_frequency_centers(min_frequency: float, max_frequency: float, count: int) -> list[float]:
    lo = math.log(min_frequency)
    hi = math.log(max_frequency)
    return [math.exp(lo + (hi - lo) * i / (count - 1)) for i in range(count)]


def extract_specific_events(clip: Clip, times: list[float], centers: list[float], window_ms: float) -> list[EventSpectrum]:
    events = []
    for index, time_s in enumerate(times, start=1):
        peak_index = local_peak_index(clip.samples, clip.sample_rate, time_s, radius_s=0.12)
        values = log_spectrum_db(clip.samples, clip.sample_rate, peak_index, centers, window_ms)
        duration_ms = estimate_event_duration_ms(clip.samples, clip.sample_rate, peak_index)
        events.append(make_event(clip, index, peak_index, duration_ms, values))
    return events


def extract_detected_events(clip: Clip, centers: list[float], window_ms: float) -> list[EventSpectrum]:
    events = []
    segments = detect_rms_segments(clip.samples, clip.sample_rate)
    for index, (start, end) in enumerate(segments, start=1):
        peak_index = max(range(start, end), key=lambda i: abs(clip.samples[i]))
        duration_ms = 1000.0 * (end - start) / clip.sample_rate
        values = log_spectrum_db(clip.samples, clip.sample_rate, peak_index, centers, window_ms)
        events.append(make_event(clip, index, peak_index, duration_ms, values))
    return events


def detect_rms_segments(samples: list[float], sample_rate: int) -> list[tuple[int, int]]:
    frame = max(1, int(sample_rate * 0.02))
    hop = frame
    rms_values = []
    for start in range(0, max(1, len(samples) - frame + 1), hop):
        chunk = samples[start:start + frame]
        rms = math.sqrt(sum(v * v for v in chunk) / len(chunk))
        rms_values.append((start, min(start + frame, len(samples)), rms))
    if not rms_values:
        return []
    sorted_rms = sorted(r for _, _, r in rms_values)
    noise = percentile(sorted_rms, 20)
    threshold = max(noise * 3.0, 0.0012)
    active = [(start, end) for start, end, rms in rms_values if rms >= threshold]
    merged = []
    for start, end in active:
        if not merged or start - merged[-1][1] > int(sample_rate * 0.12):
            merged.append([start, end])
        else:
            merged[-1][1] = end
    # Keep plausible blow-sized events with tolerance around the user's 300-470 ms estimate.
    result = []
    for start, end in merged:
        duration_ms = 1000.0 * (end - start) / sample_rate
        if 180 <= duration_ms <= 900:
            result.append((start, end))
    return result


def estimate_event_duration_ms(samples: list[float], sample_rate: int, peak_index: int) -> float:
    frame = max(1, int(sample_rate * 0.02))
    start_search = max(0, peak_index - int(sample_rate * 0.7))
    end_search = min(len(samples), peak_index + int(sample_rate * 0.7))
    local = samples[start_search:end_search]
    if not local:
        return 0.0
    rms_values = []
    for offset in range(0, max(1, len(local) - frame + 1), frame):
        chunk = local[offset:offset + frame]
        rms_values.append(math.sqrt(sum(v * v for v in chunk) / len(chunk)))
    threshold = max(percentile(sorted(rms_values), 20) * 3.0, max(rms_values) * 0.18)
    peak_frame = (peak_index - start_search) // frame
    left = peak_frame
    while left > 0 and rms_values[left] >= threshold:
        left -= 1
    right = peak_frame
    while right < len(rms_values) - 1 and rms_values[right] >= threshold:
        right += 1
    return 1000.0 * max(0, right - left) * frame / sample_rate


def local_peak_index(samples: list[float], sample_rate: int, time_s: float, radius_s: float) -> int:
    center = round(time_s * sample_rate)
    radius = round(radius_s * sample_rate)
    start = max(0, center - radius)
    end = min(len(samples), center + radius + 1)
    return max(range(start, end), key=lambda i: abs(samples[i]))


def log_spectrum_db(samples: list[float], sample_rate: int, center_index: int, centers: list[float], window_ms: float) -> list[float]:
    size = next_power_of_two(max(128, int(sample_rate * window_ms / 1000.0)))
    half = size // 2
    start = center_index - half
    frame = [samples[i] if 0 <= i < len(samples) else 0.0 for i in range(start, start + size)]
    weights = hann(size)
    return [goertzel_db(frame, weights, sample_rate, frequency) for frequency in centers]


def goertzel_db(frame: list[float], weights: list[float], sample_rate: int, target_frequency: float) -> float:
    n = len(frame)
    k = int(0.5 + n * target_frequency / sample_rate)
    omega = 2.0 * math.pi * k / n
    coeff = 2.0 * math.cos(omega)
    q0 = q1 = q2 = 0.0
    for sample, weight in zip(frame, weights):
        q0 = coeff * q1 - q2 + sample * weight
        q2 = q1
        q1 = q0
    power = max(q1 * q1 + q2 * q2 - coeff * q1 * q2, EPS)
    coherent_gain = sum(weights) / n
    amplitude = math.sqrt(power) / max((n / 2.0) * coherent_gain, EPS)
    return 20.0 * math.log10(max(amplitude, EPS))


def make_event(clip: Clip, index: int, peak_index: int, duration_ms: float, values: list[float]) -> EventSpectrum:
    peak_db = max(values)
    return EventSpectrum(
        source=clip.source,
        clip=clip.wav_name,
        label=clip.label,
        event_index=index,
        peak_time_s=peak_index / clip.sample_rate,
        duration_ms=duration_ms,
        raw_peak=abs(clip.samples[peak_index]),
        values_db=values,
        normalized_db=[value - peak_db for value in values],
    )


def hann(n: int) -> list[float]:
    if n <= 1:
        return [1.0]
    return [0.5 - 0.5 * math.cos(2.0 * math.pi * i / (n - 1)) for i in range(n)]


def next_power_of_two(value: int) -> int:
    power = 1
    while power < value:
        power *= 2
    return power


def percentile(sorted_values: list[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    index = (len(sorted_values) - 1) * pct / 100.0
    low = math.floor(index)
    high = math.ceil(index)
    if low == high:
        return sorted_values[int(index)]
    weight = index - low
    return sorted_values[low] * (1 - weight) + sorted_values[high] * weight


def correlation(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    mean_a = statistics.mean(a)
    mean_b = statistics.mean(b)
    da = [v - mean_a for v in a]
    db = [v - mean_b for v in b]
    denom = math.sqrt(sum(v * v for v in da) * sum(v * v for v in db))
    if denom <= EPS:
        return 0.0
    return sum(x * y for x, y in zip(da, db)) / denom


def mean_abs_delta(a: list[float], b: list[float]) -> float:
    return sum(abs(x - y) for x, y in zip(a, b)) / len(a)


def write_event_csv(path: Path, centers: list[float], events: list[EventSpectrum]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["frequency_hz", *[event_name(e) + "_dbfs" for e in events], *[event_name(e) + "_normalized_db" for e in events]])
        for index, frequency in enumerate(centers):
            writer.writerow([
                f"{frequency:.6f}",
                *[f"{event.values_db[index]:.6f}" for event in events],
                *[f"{event.normalized_db[index]:.6f}" for event in events],
            ])


def write_template_csv(path: Path, centers: list[float], events: list[EventSpectrum]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["frequency_hz", "min_normalized_db", "p10_normalized_db", "mean_normalized_db", "p90_normalized_db", "max_normalized_db", "event_count"])
        for index, frequency in enumerate(centers):
            values = sorted(event.normalized_db[index] for event in events)
            if not values:
                writer.writerow([f"{frequency:.6f}", "", "", "", "", "", 0])
                continue
            writer.writerow([
                f"{frequency:.6f}",
                f"{min(values):.6f}",
                f"{percentile(values, 10):.6f}",
                f"{statistics.mean(values):.6f}",
                f"{percentile(values, 90):.6f}",
                f"{max(values):.6f}",
                len(values),
            ])


def write_similarity_csv(path: Path, events: list[EventSpectrum]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["event_a", "event_b", "correlation", "mean_abs_delta_db"])
        for i, a in enumerate(events):
            for b in events[i + 1:]:
                writer.writerow([
                    event_name(a),
                    event_name(b),
                    f"{correlation(a.normalized_db, b.normalized_db):.6f}",
                    f"{mean_abs_delta(a.normalized_db, b.normalized_db):.6f}",
                ])


def event_name(event: EventSpectrum) -> str:
    stem = Path(event.clip).stem.replace("-", "_")
    return f"{stem}_event{event.event_index}_peak{event.peak_time_s:.3f}s"


def print_specific_report(events: list[EventSpectrum]) -> None:
    print("Specific three-blow similarity:")
    for event in events:
        print(f"  event {event.event_index}: peak={event.peak_time_s:.5f}s duration={event.duration_ms:.1f}ms rawPeak={event.raw_peak:.5f}")
    for i, a in enumerate(events):
        for b in events[i + 1:]:
            print(f"  {event_name(a)} vs {event_name(b)}: corr={correlation(a.normalized_db, b.normalized_db):.3f}, meanAbsDelta={mean_abs_delta(a.normalized_db, b.normalized_db):.2f} dB")


def print_all_report(events: list[EventSpectrum], centers: list[float], out_dir: Path) -> None:
    print(f"\nExtracted {len(events)} blow-like events from recordings")
    if events:
        corr_values = []
        for i, a in enumerate(events):
            for b in events[i + 1:]:
                corr_values.append(correlation(a.normalized_db, b.normalized_db))
        if corr_values:
            print(f"All-event normalized-curve correlation: median={statistics.median(corr_values):.3f}, p10={percentile(sorted(corr_values), 10):.3f}, p90={percentile(sorted(corr_values), 90):.3f}")
    print(f"Wrote {out_dir / '1782605969288_three_blow_log_spectra.csv'}")
    print(f"Wrote {out_dir / '1782605969288_three_blow_similarity.csv'}")
    print(f"Wrote {out_dir / 'manual_blow_event_log_spectra.csv'}")
    print(f"Wrote {out_dir / 'manual_blow_log_frequency_template_min_max.csv'}")
    print(f"Wrote {out_dir / 'all_blow_event_log_spectra.csv'}")
    print(f"Wrote {out_dir / 'all_blow_event_similarity.csv'}")
    print(f"Wrote {out_dir / 'blow_log_frequency_template_min_max.csv'}")


if __name__ == "__main__":
    main()
