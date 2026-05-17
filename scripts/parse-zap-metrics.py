#!/usr/bin/env python3
"""
parse-zap-metrics.py — Aggregate ZapMetricsLogger events from a logcat capture.

Reads a logcat file (or stdin via "-") emitted by ZapMetricsLogger (player/) and
ProviderRepositoryImpl (data/). Both emit single-line JSON events under the
`ZapMetrics` tag. Reconstructs zap sessions and computes gap statistics consumed
by the M7 (fast channel zap) milestone.

Usage:
    python3 scripts/parse-zap-metrics.py path/to/logcat.txt
    adb logcat -d | python3 scripts/parse-zap-metrics.py -

The parser is tolerant: empty input or absence of `first_frame` events yields a
well-formed JSON with `total_zaps: 0` and `null` percentiles.

Event schema (one JSON object per line):
    {"event": "intent",       "channelId": <long>, "ts_ms": <long>, "elapsed_since_intent_ms": 0}
    {"event": "release",                            "ts_ms": <long>, "elapsed_since_intent_ms": <long>}
    {"event": "codec_start",                        "ts_ms": <long>, "elapsed_since_intent_ms": <long>}
    {"event": "surface_gen", "gen": <int>,          "ts_ms": <long>, "elapsed_since_intent_ms": <long>}
    {"event": "first_frame",                        "ts_ms": <long>, "elapsed_since_intent_ms": <long>}
    {"event": "epg_request", "streamId": <long>,    "ts_ms": <long>, "elapsed_since_intent_ms": <long>}

Zap reconstruction: each `intent` opens a new zap session. The next `first_frame`
event after that intent (before the following intent, if any) closes it. Gap
metrics: gap_ms = first_frame.ts_ms - intent.ts_ms. If a `release` event falls
between intent and first_frame, release_to_first_frame_ms = first_frame.ts_ms -
release.ts_ms (the perceived black-screen window).

EPG burst window: count of `epg_request` events whose absolute ts_ms is within
N ms of the intent ts_ms (N defaulting to 500 ms).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from typing import Any, Dict, List, Optional, Sequence


# Logcat line forms we want to parse:
#   "I/ZapMetrics(  pid): {...json...}"        (-v brief)
#   "<date> <time>  pid  tid I ZapMetrics: {...json...}"  (-v threadtime)
#   "I ZapMetrics: {...json...}"                (-v tag)
LOGCAT_JSON_RE = re.compile(r"ZapMetrics[^{]*?(\{[^\n\r]*\})")

BURST_WINDOW_MS = 500


def parse_events(stream) -> List[Dict[str, Any]]:
    """Read a logcat-like text stream and return the parsed ZapMetrics events."""
    events: List[Dict[str, Any]] = []
    for raw_line in stream:
        line = raw_line.rstrip("\n").rstrip("\r")
        match = LOGCAT_JSON_RE.search(line)
        if not match:
            continue
        try:
            obj = json.loads(match.group(1))
        except json.JSONDecodeError:
            continue
        if not isinstance(obj, dict) or "event" not in obj or "ts_ms" not in obj:
            continue
        events.append(obj)
    events.sort(key=lambda e: e.get("ts_ms", 0))
    return events


def reconstruct_zaps(events: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Pair each `intent` event with the first subsequent `first_frame`."""
    zaps: List[Dict[str, Any]] = []
    current: Optional[Dict[str, Any]] = None

    for ev in events:
        kind = ev.get("event")
        ts = ev.get("ts_ms", 0)

        if kind == "intent":
            if current is not None:
                zaps.append(current)
            current = {
                "channel_id": ev.get("channelId"),
                "intent_ts": ts,
                "release_ts": None,
                "codec_start_ts": None,
                "first_frame_ts": None,
                "surface_gen": None,
                "epg_requests": [],
            }
            continue

        if current is None:
            # Event predates any intent (e.g. logcat truncation) — skip.
            continue

        if kind == "release" and current["release_ts"] is None:
            current["release_ts"] = ts
        elif kind == "codec_start" and current["codec_start_ts"] is None:
            current["codec_start_ts"] = ts
        elif kind == "surface_gen":
            current["surface_gen"] = ev.get("gen")
        elif kind == "first_frame" and current["first_frame_ts"] is None:
            current["first_frame_ts"] = ts
        elif kind == "epg_request":
            current["epg_requests"].append(ts)

    if current is not None:
        zaps.append(current)

    return zaps


def _percentile(values: Sequence[int], pct: float) -> Optional[int]:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    # Nearest-rank method, clamped — matches a typical engineering p95 reading.
    k = max(0, min(len(ordered) - 1, int(round(pct / 100.0 * (len(ordered) - 1)))))
    return ordered[k]


def summarize_int_distribution(values: Sequence[int]) -> Dict[str, Optional[int]]:
    if not values:
        return {"p50": None, "p95": None, "min": None, "max": None, "avg": None}
    return {
        "p50": _percentile(values, 50),
        "p95": _percentile(values, 95),
        "min": min(values),
        "max": max(values),
        "avg": int(round(sum(values) / len(values))),
    }


def build_report(zaps: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    detailed: List[Dict[str, Any]] = []
    gap_ms_values: List[int] = []
    release_to_ff_values: List[int] = []
    burst_values: List[int] = []

    for zap in zaps:
        intent_ts = zap["intent_ts"]
        ff_ts = zap["first_frame_ts"]
        release_ts = zap["release_ts"]
        gap_ms = (ff_ts - intent_ts) if ff_ts is not None else None
        release_to_ff = (
            ff_ts - release_ts
            if (ff_ts is not None and release_ts is not None and release_ts <= ff_ts)
            else None
        )
        burst = sum(
            1
            for ts in zap["epg_requests"]
            if 0 <= ts - intent_ts <= BURST_WINDOW_MS
        )

        if gap_ms is not None:
            gap_ms_values.append(gap_ms)
        if release_to_ff is not None:
            release_to_ff_values.append(release_to_ff)
        burst_values.append(burst)

        detailed.append({
            "channel_id": zap["channel_id"],
            "intent_ts": intent_ts,
            "release_ts": release_ts,
            "codec_start_ts": zap["codec_start_ts"],
            "first_frame_ts": ff_ts,
            "surface_gen": zap["surface_gen"],
            "gap_ms": gap_ms,
            "release_to_first_frame_ms": release_to_ff,
            "epg_requests_count": len(zap["epg_requests"]),
            "epg_in_flight_t500": burst,
        })

    return {
        "total_zaps": len(zaps),
        "gap_ms": summarize_int_distribution(gap_ms_values),
        "release_to_first_frame_ms": summarize_int_distribution(release_to_ff_values),
        "epg_burst_at_t500": summarize_int_distribution(burst_values),
        "zaps": detailed,
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument(
        "logcat",
        help="Path to a logcat capture file, or '-' to read from stdin.",
    )
    args = parser.parse_args(argv)

    if args.logcat == "-":
        events = parse_events(sys.stdin)
    else:
        with open(args.logcat, "r", encoding="utf-8", errors="replace") as fh:
            events = parse_events(fh)

    zaps = reconstruct_zaps(events)
    report = build_report(zaps)
    json.dump(report, sys.stdout, indent=2, sort_keys=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
