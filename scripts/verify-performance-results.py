#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fail-closed verification of AuroraSMS full performance evidence.

The verifier consumes AndroidX Benchmark JSON artifacts plus the raw output from
the separate MemoryBenchmark instrumentation invocation.  It deliberately uses
the raw per-iteration values instead of trusting pre-computed summaries.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Sequence


EXPECTED_TIMING_REPEATS = 30
EXPECTED_FRAME_REPEATS = 10
EXPECTED_MEMORY_SAMPLES = 10
EXPECTED_WARMUPS = 5
EXPECTED_BENCHMARK_RECORDS = 10
EXPECTED_PERFETTO_TRACES = 260
MAXIMUM_PSS_KIB_EXCLUSIVE = 150 * 1024
MAXIMUM_SLOW_FRAME_FRACTION_EXCLUSIVE = 0.01
FROZEN_FRAME_MILLIS = 700.0


@dataclass(frozen=True)
class TimingSpec:
    label: str
    class_name: str
    test_name: str
    metric_name: str
    expected_warmups: int
    p50_limit_ms: float | None
    p95_limit_ms: float | None


@dataclass(frozen=True)
class FrameSpec:
    label: str
    class_name: str
    test_name: str


@dataclass(frozen=True)
class BenchmarkRecord:
    source: Path
    data: dict[str, Any]


@dataclass(frozen=True)
class TimingResult:
    label: str
    test_name: str
    p50_ms: float
    p95_ms: float
    repeats: int
    warmups: int
    budgeted: bool


@dataclass(frozen=True)
class FrameResult:
    label: str
    test_name: str
    frame_count: int
    slow_frame_count: int
    slow_frame_fraction: float
    frozen_frame_count: int
    repeats: int
    warmups: int


@dataclass(frozen=True)
class MemoryResult:
    median_pss_kib: float
    sample_count: int


@dataclass(frozen=True)
class VerificationReport:
    json_files: tuple[Path, ...]
    perfetto_trace_count: int
    timing_results: tuple[TimingResult, ...]
    frame_results: tuple[FrameResult, ...]
    memory_result: MemoryResult


class EvidenceError(Exception):
    """The supplied evidence is incomplete, ambiguous, or outside a budget."""


TIMING_SPECS: tuple[TimingSpec, ...] = (
    TimingSpec(
        label="Warm inbox (without compilation)",
        class_name="org.aurorasms.macrobenchmark.StartupBenchmark",
        test_name="warmInboxWithoutCompilation",
        metric_name="timeToFullDisplayMs",
        expected_warmups=0,
        p50_limit_ms=None,
        p95_limit_ms=None,
    ),
    TimingSpec(
        label="Warm inbox (baseline profile)",
        class_name="org.aurorasms.macrobenchmark.StartupBenchmark",
        test_name="warmInboxWithBaselineProfile",
        metric_name="timeToFullDisplayMs",
        expected_warmups=EXPECTED_WARMUPS,
        p50_limit_ms=300.0,
        p95_limit_ms=500.0,
    ),
    TimingSpec(
        label="Thread open (without compilation)",
        class_name="org.aurorasms.macrobenchmark.ConversationFrameBenchmark",
        test_name="threadOpenWithoutCompilation",
        metric_name="AuroraThreadOpenFirstMs",
        expected_warmups=0,
        p50_limit_ms=None,
        p95_limit_ms=None,
    ),
    TimingSpec(
        label="Thread open (baseline profile)",
        class_name="org.aurorasms.macrobenchmark.ConversationFrameBenchmark",
        test_name="threadOpenWithBaselineProfile",
        metric_name="AuroraThreadOpenFirstMs",
        expected_warmups=EXPECTED_WARMUPS,
        p50_limit_ms=250.0,
        p95_limit_ms=450.0,
    ),
    TimingSpec(
        label="Search (without compilation)",
        class_name="org.aurorasms.macrobenchmark.SearchJumpBenchmark",
        test_name="searchWithoutCompilation",
        metric_name="AuroraSearchResultsFirstMs",
        expected_warmups=0,
        p50_limit_ms=None,
        p95_limit_ms=None,
    ),
    TimingSpec(
        label="Search (baseline profile)",
        class_name="org.aurorasms.macrobenchmark.SearchJumpBenchmark",
        test_name="searchWithBaselineProfile",
        metric_name="AuroraSearchResultsFirstMs",
        expected_warmups=EXPECTED_WARMUPS,
        p50_limit_ms=120.0,
        p95_limit_ms=220.0,
    ),
    TimingSpec(
        label="Exact old-result jump (without compilation)",
        class_name="org.aurorasms.macrobenchmark.SearchJumpBenchmark",
        test_name="exactOldJumpWithoutCompilation",
        metric_name="AuroraExactJumpFirstMs",
        expected_warmups=0,
        p50_limit_ms=None,
        p95_limit_ms=None,
    ),
    TimingSpec(
        label="Exact old-result jump (baseline profile)",
        class_name="org.aurorasms.macrobenchmark.SearchJumpBenchmark",
        test_name="exactOldJumpWithBaselineProfile",
        metric_name="AuroraExactJumpFirstMs",
        expected_warmups=EXPECTED_WARMUPS,
        p50_limit_ms=350.0,
        p95_limit_ms=650.0,
    ),
)

FRAME_SPECS: tuple[FrameSpec, ...] = (
    FrameSpec(
        label="20k-thread inbox fling",
        class_name="org.aurorasms.macrobenchmark.InboxFrameBenchmark",
        test_name="inboxFlingAtTwentyThousandThreads",
    ),
    FrameSpec(
        label="250k-message thread fling/prepend",
        class_name="org.aurorasms.macrobenchmark.ConversationFrameBenchmark",
        test_name="threadFlingAndPrependAtTwoHundredFiftyThousandMessages",
    ),
)

MEMORY_CLASS_NAME = "org.aurorasms.macrobenchmark.MemoryBenchmark"
MEMORY_TEST_NAME = "fixedTextBrowsePss"
MEMORY_STATUS_PREFIX = r"^\s*INSTRUMENTATION_STATUS:\s*"
MEMORY_CLASS_PATTERN = re.compile(
    MEMORY_STATUS_PREFIX + r"class\s*=\s*([^\s]+)\s*$",
    re.MULTILINE,
)
MEMORY_TEST_PATTERN = re.compile(
    MEMORY_STATUS_PREFIX + r"test\s*=\s*([^\s]+)\s*$",
    re.MULTILINE,
)
MEMORY_MEDIAN_PATTERN = re.compile(
    MEMORY_STATUS_PREFIX + r"auroraPssMedianKiB\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*$",
    re.MULTILINE,
)
MEMORY_COUNT_PATTERN = re.compile(
    MEMORY_STATUS_PREFIX + r"auroraPssSampleCount\s*=\s*(\d+)\s*$",
    re.MULTILINE,
)
MEMORY_SAMPLES_PATTERN = re.compile(
    MEMORY_STATUS_PREFIX + r"auroraPssSamplesKiB\s*=\s*([0-9]+(?:,[0-9]+)*)\s*$",
    re.MULTILINE,
)
MEMORY_OK_PATTERN = re.compile(r"^\s*OK \(1 test\)\s*$", re.MULTILINE)
MEMORY_ANY_OK_PATTERN = re.compile(r"^\s*OK\s*\([^\r\n]*\)\s*$", re.MULTILINE)
MEMORY_CODE_PATTERN = re.compile(
    r"^\s*INSTRUMENTATION_CODE\s*:\s*(-?\d+)\s*$",
    re.MULTILINE,
)
MEMORY_FAILURE_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bFAILURES!!!"),
    re.compile(r"\bINSTRUMENTATION_FAILED\b"),
    re.compile(r"\bINSTRUMENTATION_CODE\s*:\s*(?!-1\b)-?\d+\b"),
    re.compile(r"\bBUILD FAILED\b"),
)


def _duplicate_key_rejector(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceError(f"duplicate JSON object key {key!r}")
        result[key] = value
    return result


def _reject_non_finite_json(value: str) -> None:
    raise EvidenceError(f"non-finite JSON number {value!r}")


def _discover_files(inputs: Sequence[Path], suffix: str) -> tuple[Path, ...]:
    if not inputs:
        raise EvidenceError("no evidence inputs were supplied")
    discovered: dict[Path, None] = {}
    for supplied in inputs:
        path = supplied.expanduser()
        if not path.exists():
            raise EvidenceError(f"evidence input does not exist: {path}")
        if path.is_file():
            candidates = (path,)
        elif path.is_dir():
            candidates = tuple(sorted(path.rglob(f"*{suffix}")))
        else:
            raise EvidenceError(f"evidence input is not a regular file or directory: {path}")
        for candidate in candidates:
            if candidate.is_file():
                discovered[candidate.resolve()] = None
    if not discovered:
        raise EvidenceError(f"no {suffix} evidence files were found")
    return tuple(sorted(discovered))


def _load_benchmark_records(
    benchmark_inputs: Sequence[Path],
) -> tuple[tuple[Path, ...], tuple[BenchmarkRecord, ...]]:
    json_files = _discover_files(benchmark_inputs, ".json")
    records: list[BenchmarkRecord] = []
    benchmark_json_files: list[Path] = []
    for path in json_files:
        try:
            with path.open("r", encoding="utf-8") as stream:
                document = json.load(
                    stream,
                    object_pairs_hook=_duplicate_key_rejector,
                    parse_constant=_reject_non_finite_json,
                )
        except EvidenceError as error:
            raise EvidenceError(f"invalid AndroidX JSON {path}: {error}") from error
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise EvidenceError(f"cannot parse AndroidX JSON {path}: {error}") from error

        if not isinstance(document, dict) or "benchmarks" not in document:
            # Output directories can also contain runner metadata JSON.  It is
            # ignored only when it is valid JSON and cannot masquerade as an
            # AndroidX Benchmark document.
            continue
        benchmarks = document["benchmarks"]
        if not isinstance(benchmarks, list):
            raise EvidenceError(f"AndroidX JSON {path} has a non-list 'benchmarks' field")
        benchmark_json_files.append(path)
        for index, benchmark in enumerate(benchmarks):
            if not isinstance(benchmark, dict):
                raise EvidenceError(
                    f"AndroidX JSON {path} benchmark index {index} is not an object"
                )
            records.append(BenchmarkRecord(path, benchmark))

    if not benchmark_json_files:
        raise EvidenceError("no AndroidX Benchmark JSON document was found")
    if len(benchmark_json_files) != 1:
        raise EvidenceError(
            "full evidence must contain exactly one AndroidX Benchmark JSON document; "
            f"found {len(benchmark_json_files)}: "
            + ", ".join(str(path) for path in benchmark_json_files)
        )
    if not records:
        raise EvidenceError("AndroidX Benchmark JSON contains no benchmark results")
    if len(records) != EXPECTED_BENCHMARK_RECORDS:
        raise EvidenceError(
            "AndroidX Benchmark JSON must contain exactly "
            f"{EXPECTED_BENCHMARK_RECORDS} benchmark records; found {len(records)}"
        )
    return tuple(benchmark_json_files), tuple(records)


def _expected_record_keys() -> frozenset[tuple[str, str]]:
    return frozenset(
        (spec.class_name, spec.test_name)
        for spec in (*TIMING_SPECS, *FRAME_SPECS)
    )


def _verify_exact_record_set(records: Sequence[BenchmarkRecord]) -> None:
    expected = _expected_record_keys()
    if len(expected) != EXPECTED_BENCHMARK_RECORDS:
        raise AssertionError("internal expected benchmark record set is not unique")

    actual: list[tuple[str, str]] = []
    for index, record in enumerate(records):
        class_name = record.data.get("className")
        test_name = record.data.get("name")
        if not isinstance(class_name, str) or not class_name.strip():
            raise EvidenceError(
                f"benchmark record {index} has an invalid className {class_name!r}"
            )
        if not isinstance(test_name, str) or not test_name.strip():
            raise EvidenceError(
                f"benchmark record {index} has an invalid name {test_name!r}"
            )
        actual.append((class_name, test_name))

    duplicates = sorted(key for key in set(actual) if actual.count(key) != 1)
    if duplicates:
        rendered = ", ".join(f"{class_name}#{test_name}" for class_name, test_name in duplicates)
        raise EvidenceError(f"duplicate expected benchmark record(s): {rendered}")

    actual_set = frozenset(actual)
    missing = sorted(expected - actual_set)
    extras = sorted(actual_set - expected)
    if missing or extras:
        details: list[str] = []
        if missing:
            details.append(
                "missing "
                + ", ".join(f"{class_name}#{test_name}" for class_name, test_name in missing)
            )
        if extras:
            details.append(
                "unexpected "
                + ", ".join(f"{class_name}#{test_name}" for class_name, test_name in extras)
            )
        raise EvidenceError("benchmark record set mismatch: " + "; ".join(details))


def _discover_perfetto_traces(benchmark_inputs: Sequence[Path]) -> tuple[Path, ...]:
    roots: dict[Path, None] = {}
    for supplied in benchmark_inputs:
        path = supplied.expanduser()
        if not path.exists():
            raise EvidenceError(f"evidence input does not exist: {path}")
        root = path if path.is_dir() else path.parent
        roots[root.resolve()] = None

    traces: dict[Path, None] = {}
    for root in roots:
        for candidate in root.rglob("*.perfetto-trace"):
            if candidate.is_file():
                traces[candidate.resolve()] = None
    return tuple(sorted(traces))


def _verify_perfetto_outputs(
    benchmark_inputs: Sequence[Path],
    records: Sequence[BenchmarkRecord],
) -> int:
    referenced: dict[str, str] = {}
    for record in records:
        test_name = str(record.data.get("name"))
        repeats = record.data.get("repeatIterations")
        if isinstance(repeats, bool) or not isinstance(repeats, int) or repeats <= 0:
            raise EvidenceError(
                f"{test_name}: repeatIterations must be a positive integer before "
                "profiler output verification"
            )
        outputs = record.data.get("profilerOutputs")
        if not isinstance(outputs, list):
            raise EvidenceError(f"{test_name}: profilerOutputs must be a list")
        if len(outputs) != repeats:
            raise EvidenceError(
                f"{test_name}: profilerOutputs has {len(outputs)} entries; "
                f"expected exactly repeatIterations={repeats}"
            )
        for index, output in enumerate(outputs):
            context = f"{test_name}: profilerOutputs[{index}]"
            if not isinstance(output, dict):
                raise EvidenceError(f"{context} must be an object")
            if output.get("type") != "PerfettoTrace":
                raise EvidenceError(
                    f"{context}.type must be 'PerfettoTrace', got {output.get('type')!r}"
                )
            filename = output.get("filename")
            if not isinstance(filename, str) or not filename.strip():
                raise EvidenceError(f"{context}.filename must be nonblank")
            if filename != filename.strip() or not filename.endswith(".perfetto-trace"):
                raise EvidenceError(
                    f"{context}.filename must end with '.perfetto-trace' without padding"
                )
            relative = PurePosixPath(filename)
            if relative.is_absolute() or ".." in relative.parts or "\\" in filename:
                raise EvidenceError(f"{context}.filename is not a safe relative path")
            basename = relative.name
            if not basename:
                raise EvidenceError(f"{context}.filename has no basename")
            if basename in referenced:
                raise EvidenceError(
                    f"duplicate/ambiguous Perfetto filename basename {basename!r} in "
                    f"{referenced[basename]} and {context}"
                )
            referenced[basename] = context

    if len(referenced) != EXPECTED_PERFETTO_TRACES:
        raise EvidenceError(
            f"full evidence must reference exactly {EXPECTED_PERFETTO_TRACES} unique "
            f"Perfetto traces; found {len(referenced)}"
        )

    trace_files = _discover_perfetto_traces(benchmark_inputs)
    by_basename: dict[str, list[Path]] = {}
    for trace in trace_files:
        by_basename.setdefault(trace.name, []).append(trace)

    for basename, context in referenced.items():
        matches = by_basename.get(basename, [])
        if len(matches) != 1:
            raise EvidenceError(
                f"{context} references {basename!r}, which resolves to {len(matches)} "
                "files within the supplied results; expected exactly one"
            )

    unreferenced = sorted(
        trace
        for basename, paths in by_basename.items()
        if basename not in referenced
        for trace in paths
    )
    if unreferenced:
        raise EvidenceError(
            "supplied results contain unreferenced Perfetto trace file(s): "
            + ", ".join(str(path) for path in unreferenced)
        )
    if len(trace_files) != EXPECTED_PERFETTO_TRACES:
        raise EvidenceError(
            f"full evidence must contain exactly {EXPECTED_PERFETTO_TRACES} Perfetto "
            f"trace files; found {len(trace_files)}"
        )
    return len(trace_files)


def _required_record(
    records: Sequence[BenchmarkRecord], class_name: str, test_name: str
) -> BenchmarkRecord:
    named = [record for record in records if record.data.get("name") == test_name]
    if not named:
        raise EvidenceError(f"missing required benchmark result {class_name}#{test_name}")
    if len(named) != 1:
        sources = ", ".join(str(record.source) for record in named)
        raise EvidenceError(
            f"duplicate/ambiguous benchmark result {test_name!r} in: {sources}"
        )
    record = named[0]
    actual_class = record.data.get("className")
    if actual_class != class_name:
        raise EvidenceError(
            f"benchmark {test_name!r} has className {actual_class!r}; "
            f"expected {class_name!r} ({record.source})"
        )
    return record


def _required_integer(
    benchmark: BenchmarkRecord, field: str, expected: int, test_name: str
) -> int:
    value = benchmark.data.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise EvidenceError(
            f"{test_name}: {field} must be integer {expected}, got {value!r}"
        )
    if value != expected:
        raise EvidenceError(
            f"{test_name}: {field} must be {expected} for a full run, got {value}"
        )
    return value


def _finite_number(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"{context} must be a finite number, got {value!r}")
    result = float(value)
    if not math.isfinite(result):
        raise EvidenceError(f"{context} must be finite, got {value!r}")
    return result


def _metric_runs(
    benchmark: BenchmarkRecord,
    metric_name: str,
    expected_repeats: int,
    sampled: bool,
) -> list[Any]:
    test_name = str(benchmark.data.get("name"))
    wanted_bucket = "sampledMetrics" if sampled else "metrics"
    other_bucket = "metrics" if sampled else "sampledMetrics"
    metrics = benchmark.data.get(wanted_bucket)
    other_metrics = benchmark.data.get(other_bucket)
    if not isinstance(metrics, dict):
        raise EvidenceError(f"{test_name}: {wanted_bucket} must be an object")
    if not isinstance(other_metrics, dict):
        raise EvidenceError(f"{test_name}: {other_bucket} must be an object")
    if metric_name in other_metrics:
        raise EvidenceError(
            f"{test_name}: metric {metric_name!r} is ambiguous across metrics buckets"
        )
    metric = metrics.get(metric_name)
    if not isinstance(metric, dict):
        raise EvidenceError(
            f"{test_name}: missing required metric {metric_name!r} in {wanted_bucket}"
        )
    runs = metric.get("runs")
    if not isinstance(runs, list):
        raise EvidenceError(f"{test_name}: {metric_name}.runs must be a list")
    if len(runs) != expected_repeats:
        raise EvidenceError(
            f"{test_name}: {metric_name}.runs has {len(runs)} iterations; "
            f"expected {expected_repeats}"
        )
    return runs


def _linear_interpolated_percentile(
    values: Sequence[float], percentile: int
) -> float:
    if not values:
        raise EvidenceError("cannot calculate a percentile from an empty value set")
    if percentile < 0 or percentile > 100:
        raise EvidenceError(f"percentile must be between 0 and 100, got {percentile}")
    ordered = sorted(values)
    position = (percentile / 100.0) * (len(ordered) - 1)
    lower_index = math.floor(position)
    upper_index = math.ceil(position)
    if lower_index == upper_index:
        return ordered[lower_index]
    fraction = position - lower_index
    return ordered[lower_index] + (ordered[upper_index] - ordered[lower_index]) * fraction


def _verify_timing(
    records: Sequence[BenchmarkRecord], spec: TimingSpec
) -> TimingResult:
    record = _required_record(records, spec.class_name, spec.test_name)
    repeats = _required_integer(
        record, "repeatIterations", EXPECTED_TIMING_REPEATS, spec.test_name
    )
    warmups = _required_integer(
        record, "warmupIterations", spec.expected_warmups, spec.test_name
    )
    raw_runs = _metric_runs(record, spec.metric_name, repeats, sampled=False)
    runs = [
        _finite_number(value, f"{spec.test_name}: {spec.metric_name}.runs[{index}]")
        for index, value in enumerate(raw_runs)
    ]
    if any(value < 0.0 for value in runs):
        raise EvidenceError(f"{spec.test_name}: timing metric contains a negative duration")
    p50 = _linear_interpolated_percentile(runs, 50)
    p95 = _linear_interpolated_percentile(runs, 95)
    if spec.p50_limit_ms is not None and p50 > spec.p50_limit_ms:
        raise EvidenceError(
            f"{spec.label}: P50 {p50:.3f} ms exceeds {spec.p50_limit_ms:.3f} ms"
        )
    if spec.p95_limit_ms is not None and p95 > spec.p95_limit_ms:
        raise EvidenceError(
            f"{spec.label}: P95 {p95:.3f} ms exceeds {spec.p95_limit_ms:.3f} ms"
        )
    return TimingResult(
        label=spec.label,
        test_name=spec.test_name,
        p50_ms=p50,
        p95_ms=p95,
        repeats=repeats,
        warmups=warmups,
        budgeted=spec.p50_limit_ms is not None,
    )


def _sampled_frame_runs(
    record: BenchmarkRecord, metric_name: str, repeats: int
) -> list[list[float]]:
    raw_runs = _metric_runs(record, metric_name, repeats, sampled=True)
    parsed: list[list[float]] = []
    test_name = str(record.data.get("name"))
    for iteration, raw_iteration in enumerate(raw_runs):
        if not isinstance(raw_iteration, list) or not raw_iteration:
            raise EvidenceError(
                f"{test_name}: {metric_name}.runs[{iteration}] must be a non-empty list"
            )
        parsed.append(
            [
                _finite_number(
                    value,
                    f"{test_name}: {metric_name}.runs[{iteration}][{sample}]",
                )
                for sample, value in enumerate(raw_iteration)
            ]
        )
    return parsed


def _verify_frame(records: Sequence[BenchmarkRecord], spec: FrameSpec) -> FrameResult:
    record = _required_record(records, spec.class_name, spec.test_name)
    repeats = _required_integer(
        record, "repeatIterations", EXPECTED_FRAME_REPEATS, spec.test_name
    )
    warmups = _required_integer(record, "warmupIterations", EXPECTED_WARMUPS, spec.test_name)
    durations_by_run = _sampled_frame_runs(record, "frameDurationCpuMs", repeats)
    overruns_by_run = _sampled_frame_runs(record, "frameOverrunMs", repeats)

    for iteration, (durations, overruns) in enumerate(
        zip(durations_by_run, overruns_by_run, strict=True)
    ):
        if len(durations) != len(overruns):
            raise EvidenceError(
                f"{spec.test_name}: frame metrics have mismatched sample counts in "
                f"iteration {iteration}: {len(durations)} durations vs {len(overruns)} overruns"
            )
        if any(duration < 0.0 for duration in durations):
            raise EvidenceError(
                f"{spec.test_name}: frameDurationCpuMs contains a negative duration"
            )

    durations = [value for iteration in durations_by_run for value in iteration]
    overruns = [value for iteration in overruns_by_run for value in iteration]
    if not durations or len(durations) != len(overruns):
        raise EvidenceError(f"{spec.test_name}: frame evidence is empty or misaligned")
    slow_count = sum(value > 0.0 for value in overruns)
    frozen_count = sum(value >= FROZEN_FRAME_MILLIS for value in durations)
    slow_fraction = slow_count / len(overruns)
    if slow_fraction >= MAXIMUM_SLOW_FRAME_FRACTION_EXCLUSIVE:
        raise EvidenceError(
            f"{spec.label}: {slow_count}/{len(overruns)} deadline misses "
            f"({slow_fraction * 100.0:.3f}%) is not below 1.000%"
        )
    if frozen_count:
        raise EvidenceError(
            f"{spec.label}: found {frozen_count} frame(s) at or above "
            f"{FROZEN_FRAME_MILLIS:.0f} ms"
        )
    return FrameResult(
        label=spec.label,
        test_name=spec.test_name,
        frame_count=len(durations),
        slow_frame_count=slow_count,
        slow_frame_fraction=slow_fraction,
        frozen_frame_count=frozen_count,
        repeats=repeats,
        warmups=warmups,
    )


def _load_memory_text(memory_inputs: Sequence[Path]) -> tuple[tuple[Path, ...], str]:
    files = _discover_files(memory_inputs, ".txt")
    chunks: list[str] = []
    for path in files:
        try:
            chunks.append(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError) as error:
            raise EvidenceError(f"cannot read MemoryBenchmark output {path}: {error}") from error
    return files, "\n".join(chunks)


def _verify_memory(memory_inputs: Sequence[Path]) -> MemoryResult:
    files, text = _load_memory_text(memory_inputs)
    for failure_pattern in MEMORY_FAILURE_PATTERNS:
        match = failure_pattern.search(text)
        if match:
            raise EvidenceError(
                f"MemoryBenchmark output reports failure token {match.group(0)!r} "
                f"in {', '.join(str(path) for path in files)}"
            )
    ok_markers = MEMORY_ANY_OK_PATTERN.findall(text)
    if len(ok_markers) != 1 or not MEMORY_OK_PATTERN.fullmatch(ok_markers[0]):
        raise EvidenceError(
            "MemoryBenchmark output must contain exactly one 'OK (1 test)' completion marker"
        )
    instrumentation_codes = MEMORY_CODE_PATTERN.findall(text)
    if instrumentation_codes != ["-1"]:
        raise EvidenceError(
            "MemoryBenchmark output must contain exactly one "
            f"'INSTRUMENTATION_CODE: -1' marker; got {instrumentation_codes!r}"
        )

    class_names = MEMORY_CLASS_PATTERN.findall(text)
    if not class_names:
        raise EvidenceError("MemoryBenchmark output is missing instrumentation class identity")
    if set(class_names) != {MEMORY_CLASS_NAME}:
        raise EvidenceError(
            "MemoryBenchmark output has unexpected instrumentation class value(s): "
            + ", ".join(sorted(set(class_names)))
        )
    test_names = MEMORY_TEST_PATTERN.findall(text)
    if not test_names:
        raise EvidenceError("MemoryBenchmark output is missing instrumentation test identity")
    if set(test_names) != {MEMORY_TEST_NAME}:
        raise EvidenceError(
            "MemoryBenchmark output has unexpected instrumentation test value(s): "
            + ", ".join(sorted(set(test_names)))
        )

    def unique_status_value(label: str, pattern: re.Pattern[str]) -> str:
        matches = pattern.findall(text)
        if not matches:
            raise EvidenceError(f"MemoryBenchmark output is missing {label}")
        if len(matches) != 1:
            raise EvidenceError(
                f"MemoryBenchmark output contains duplicate/ambiguous {label} values: "
                f"{', '.join(matches)}"
            )
        return matches[0]

    raw_sample_text = unique_status_value("raw PSS samples", MEMORY_SAMPLES_PATTERN)
    raw_samples = [int(value) for value in raw_sample_text.split(",")]
    sample_count = int(unique_status_value("sample count", MEMORY_COUNT_PATTERN))
    reported_median = float(unique_status_value("median PSS", MEMORY_MEDIAN_PATTERN))

    if len(raw_samples) != EXPECTED_MEMORY_SAMPLES:
        raise EvidenceError(
            f"MemoryBenchmark must contain exactly {EXPECTED_MEMORY_SAMPLES} raw PSS "
            f"samples for a full run, got {len(raw_samples)}"
        )
    if any(sample <= 0 for sample in raw_samples):
        raise EvidenceError("MemoryBenchmark raw PSS samples must all be positive KiB values")
    if sample_count != len(raw_samples):
        raise EvidenceError(
            f"MemoryBenchmark sample count {sample_count} does not match "
            f"{len(raw_samples)} raw samples"
        )
    if sample_count != EXPECTED_MEMORY_SAMPLES:
        raise EvidenceError(
            f"MemoryBenchmark sample count must be {EXPECTED_MEMORY_SAMPLES} for a full run, "
            f"got {sample_count}"
        )

    ordered_samples = sorted(raw_samples)
    center = len(ordered_samples) // 2
    recomputed_median = (
        (ordered_samples[center - 1] + ordered_samples[center]) / 2.0
        if len(ordered_samples) % 2 == 0
        else float(ordered_samples[center])
    )
    if not math.isclose(
        reported_median,
        recomputed_median,
        rel_tol=0.0,
        abs_tol=1e-9,
    ):
        raise EvidenceError(
            f"MemoryBenchmark reported median {reported_median} KiB does not match "
            f"the raw-sample statistical median {recomputed_median} KiB"
        )
    if reported_median <= 0.0:
        raise EvidenceError(
            f"MemoryBenchmark median PSS must be positive, got {reported_median} KiB"
        )
    if reported_median >= MAXIMUM_PSS_KIB_EXCLUSIVE:
        raise EvidenceError(
            f"MemoryBenchmark median PSS {reported_median / 1024.0:.3f} MiB "
            "is not below 150 MiB"
        )
    return MemoryResult(median_pss_kib=reported_median, sample_count=sample_count)


def verify_evidence(
    benchmark_inputs: Sequence[Path], memory_inputs: Sequence[Path]
) -> VerificationReport:
    json_files, records = _load_benchmark_records(benchmark_inputs)
    _verify_exact_record_set(records)
    timing_results = tuple(_verify_timing(records, spec) for spec in TIMING_SPECS)
    frame_results = tuple(_verify_frame(records, spec) for spec in FRAME_SPECS)
    perfetto_trace_count = _verify_perfetto_outputs(benchmark_inputs, records)
    memory_result = _verify_memory(memory_inputs)
    return VerificationReport(
        json_files=json_files,
        perfetto_trace_count=perfetto_trace_count,
        timing_results=timing_results,
        frame_results=frame_results,
        memory_result=memory_result,
    )


def _format_report(report: VerificationReport) -> str:
    lines = [
        "AuroraSMS full performance evidence: PASS",
        f"AndroidX Benchmark JSON files: {len(report.json_files)}",
        f"Perfetto traces bound one-to-one: {report.perfetto_trace_count}",
        "Timing (AndroidX 1.4.1 linear interpolation at p*(n-1) from raw runs):",
    ]
    for result in report.timing_results:
        kind = "budget" if result.budgeted else "comparison"
        lines.append(
            f"  - {result.label}: P50={result.p50_ms:.3f} ms, "
            f"P95={result.p95_ms:.3f} ms; {result.warmups} warmups, "
            f"{result.repeats} measured ({kind})"
        )
    lines.append("Frame timing (raw aligned frame samples):")
    for result in report.frame_results:
        lines.append(
            f"  - {result.label}: {result.slow_frame_count}/{result.frame_count} "
            f"deadline misses ({result.slow_frame_fraction * 100.0:.3f}%), "
            f"{result.frozen_frame_count} frozen; {result.warmups} warmups, "
            f"{result.repeats} measured"
        )
    lines.append(
        "Memory: "
        f"median PSS={report.memory_result.median_pss_kib / 1024.0:.3f} MiB; "
        f"{report.memory_result.sample_count} fresh-process samples"
    )
    return "\n".join(lines)


def _single_metric(runs: Sequence[float]) -> dict[str, Any]:
    ordered = sorted(runs)
    return {
        "minimum": ordered[0],
        "maximum": ordered[-1],
        "median": _linear_interpolated_percentile(ordered, 50),
        "coefficientOfVariation": 0.0,
        "runs": list(runs),
    }


def _sampled_metric(runs: Sequence[Sequence[float]]) -> dict[str, Any]:
    flattened = [value for iteration in runs for value in iteration]
    return {
        "P50": _linear_interpolated_percentile(flattened, 50),
        "P90": _linear_interpolated_percentile(flattened, 90),
        "P95": _linear_interpolated_percentile(flattened, 95),
        "P99": _linear_interpolated_percentile(flattened, 99),
        "runs": [list(iteration) for iteration in runs],
    }


def _profiler_outputs(test_name: str, repeats: int) -> list[dict[str, str]]:
    return [
        {
            "type": "PerfettoTrace",
            "label": f"Trace iteration {iteration}",
            "filename": f"{test_name}-{iteration:03d}.perfetto-trace",
        }
        for iteration in range(repeats)
    ]


def _passing_fixture() -> dict[str, Any]:
    benchmarks: list[dict[str, Any]] = []
    passing_values = {
        "warmInboxWithBaselineProfile": 200.0,
        "threadOpenWithBaselineProfile": 180.0,
        "searchWithBaselineProfile": 90.0,
        "exactOldJumpWithBaselineProfile": 280.0,
    }
    for spec in TIMING_SPECS:
        value = passing_values.get(spec.test_name, 240.0)
        runs = (
            [100.0] * 28 + [230.0] * 2
            if spec.test_name == "searchWithBaselineProfile"
            else [value] * EXPECTED_TIMING_REPEATS
        )
        benchmarks.append(
            {
                "name": spec.test_name,
                "params": {},
                "className": spec.class_name,
                "totalRunTimeNs": 1,
                "metrics": {spec.metric_name: _single_metric(runs)},
                "sampledMetrics": {},
                "warmupIterations": spec.expected_warmups,
                "repeatIterations": EXPECTED_TIMING_REPEATS,
                "thermalThrottleSleepSeconds": 0,
                "profilerOutputs": _profiler_outputs(
                    spec.test_name,
                    EXPECTED_TIMING_REPEATS,
                ),
            }
        )
    for spec in FRAME_SPECS:
        durations = [[12.0] * 20 for _ in range(EXPECTED_FRAME_REPEATS)]
        overruns = [[-4.0] * 20 for _ in range(EXPECTED_FRAME_REPEATS)]
        benchmarks.append(
            {
                "name": spec.test_name,
                "params": {},
                "className": spec.class_name,
                "totalRunTimeNs": 1,
                "metrics": {"frameCount": _single_metric([20.0] * EXPECTED_FRAME_REPEATS)},
                "sampledMetrics": {
                    "frameDurationCpuMs": _sampled_metric(durations),
                    "frameOverrunMs": _sampled_metric(overruns),
                },
                "warmupIterations": EXPECTED_WARMUPS,
                "repeatIterations": EXPECTED_FRAME_REPEATS,
                "thermalThrottleSleepSeconds": 0,
                "profilerOutputs": _profiler_outputs(
                    spec.test_name,
                    EXPECTED_FRAME_REPEATS,
                ),
            }
        )
    return {"context": {}, "benchmarks": benchmarks}


def _find_fixture_test(document: dict[str, Any], test_name: str) -> dict[str, Any]:
    matches = [item for item in document["benchmarks"] if item["name"] == test_name]
    if len(matches) != 1:
        raise AssertionError(f"self-test fixture lookup for {test_name!r} is not unique")
    return matches[0]


def _write_fixture_case(
    directory: Path,
    document: dict[str, Any],
    memory_text: str | None = None,
) -> tuple[Path, Path]:
    benchmark_dir = directory / "benchmark"
    benchmark_dir.mkdir(parents=True)
    (benchmark_dir / "results.json").write_text(
        json.dumps(document, indent=2) + "\n", encoding="utf-8"
    )
    trace_dir = benchmark_dir / "traces"
    trace_dir.mkdir()
    for benchmark in document.get("benchmarks", []):
        for output in benchmark.get("profilerOutputs", []):
            filename = output.get("filename")
            if isinstance(filename, str) and filename.endswith(".perfetto-trace"):
                (trace_dir / PurePosixPath(filename).name).write_bytes(b"perfetto fixture\n")
    memory = directory / "memory.txt"
    memory.write_text(
        memory_text if memory_text is not None else _passing_memory_text(),
        encoding="utf-8",
    )
    return benchmark_dir, memory


def _passing_memory_text() -> str:
    samples = [
        102_000,
        102_100,
        102_200,
        102_300,
        102_400,
        102_401,
        102_500,
        102_600,
        102_700,
        102_800,
    ]
    return (
        f"INSTRUMENTATION_STATUS: class={MEMORY_CLASS_NAME}\n"
        f"INSTRUMENTATION_STATUS: test={MEMORY_TEST_NAME}\n"
        "INSTRUMENTATION_STATUS: auroraPssSamplesKiB="
        + ",".join(str(sample) for sample in samples)
        + "\n"
        + "INSTRUMENTATION_STATUS: auroraPssMedianKiB=102400.5\n"
        + "INSTRUMENTATION_STATUS: auroraPssSampleCount=10\n"
        + "OK (1 test)\n"
        + "INSTRUMENTATION_CODE: -1\n"
    )


def _run_self_test() -> None:
    passing = _passing_fixture()
    rejection_cases: list[
        tuple[str, Callable[[dict[str, Any]], str | None], str]
    ] = []

    def mutate_missing_test(document: dict[str, Any]) -> None:
        document["benchmarks"] = [
            item
            for item in document["benchmarks"]
            if item["name"] != "searchWithBaselineProfile"
        ]
        return None

    def mutate_extra_record(document: dict[str, Any]) -> None:
        extra = copy.deepcopy(document["benchmarks"][0])
        extra["name"] = "unexpectedBenchmark"
        extra["profilerOutputs"] = _profiler_outputs(
            "unexpectedBenchmark",
            EXPECTED_TIMING_REPEATS,
        )
        document["benchmarks"].append(extra)
        return None

    def mutate_duplicate_record(document: dict[str, Any]) -> None:
        duplicate = copy.deepcopy(
            _find_fixture_test(document, "warmInboxWithBaselineProfile")
        )
        replacement_index = next(
            index
            for index, item in enumerate(document["benchmarks"])
            if item["name"] == "searchWithBaselineProfile"
        )
        document["benchmarks"][replacement_index] = duplicate
        return None

    def mutate_repeat_count(document: dict[str, Any]) -> None:
        _find_fixture_test(document, "warmInboxWithBaselineProfile")["repeatIterations"] = 29
        return None

    def mutate_warmup_count(document: dict[str, Any]) -> None:
        _find_fixture_test(document, "threadOpenWithBaselineProfile")["warmupIterations"] = 4
        return None

    def mutate_timing_run_count(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "threadOpenWithBaselineProfile")
        test["metrics"]["AuroraThreadOpenFirstMs"]["runs"].pop()
        return None

    def mutate_non_finite_timing(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "searchWithBaselineProfile")
        test["metrics"]["AuroraSearchResultsFirstMs"]["runs"][0] = float("nan")
        return None

    def mutate_missing_metric(document: dict[str, Any]) -> None:
        _find_fixture_test(document, "searchWithBaselineProfile")["metrics"] = {}
        return None

    def mutate_ambiguous_metric(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "searchWithBaselineProfile")
        test["sampledMetrics"]["AuroraSearchResultsFirstMs"] = _sampled_metric(
            [[90.0]] * EXPECTED_TIMING_REPEATS
        )
        return None

    def mutate_p50(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "searchWithBaselineProfile")
        test["metrics"]["AuroraSearchResultsFirstMs"] = _single_metric(
            [121.0] * EXPECTED_TIMING_REPEATS
        )
        return None

    def mutate_p95(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "warmInboxWithBaselineProfile")
        test["metrics"]["timeToFullDisplayMs"] = _single_metric(
            [200.0] * 28 + [801.0] * 2
        )
        return None

    def mutate_slow_frames(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "inboxFlingAtTwentyThousandThreads")
        overruns = [[-4.0] * 20 for _ in range(EXPECTED_FRAME_REPEATS)]
        overruns[0][0:2] = [0.1, 0.1]
        test["sampledMetrics"]["frameOverrunMs"] = _sampled_metric(overruns)
        return None

    def mutate_frozen_frame(document: dict[str, Any]) -> None:
        test = _find_fixture_test(
            document, "threadFlingAndPrependAtTwoHundredFiftyThousandMessages"
        )
        durations = [[12.0] * 20 for _ in range(EXPECTED_FRAME_REPEATS)]
        durations[3][7] = FROZEN_FRAME_MILLIS
        test["sampledMetrics"]["frameDurationCpuMs"] = _sampled_metric(durations)
        return None

    def mutate_frame_alignment(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "inboxFlingAtTwentyThousandThreads")
        test["sampledMetrics"]["frameDurationCpuMs"]["runs"][0].pop()
        return None

    def mutate_frame_run_count(document: dict[str, Any]) -> None:
        test = _find_fixture_test(document, "inboxFlingAtTwentyThousandThreads")
        test["sampledMetrics"]["frameDurationCpuMs"]["runs"].pop()
        return None

    def mutate_missing_profiler_output(document: dict[str, Any]) -> None:
        _find_fixture_test(document, "warmInboxWithBaselineProfile")[
            "profilerOutputs"
        ].pop()
        return None

    def mutate_profiler_type(document: dict[str, Any]) -> None:
        output = _find_fixture_test(document, "warmInboxWithBaselineProfile")[
            "profilerOutputs"
        ][0]
        output["type"] = "MethodTrace"
        return None

    def mutate_profiler_filename(document: dict[str, Any]) -> None:
        output = _find_fixture_test(document, "warmInboxWithBaselineProfile")[
            "profilerOutputs"
        ][0]
        output["filename"] = "tampered.trace"
        return None

    def mutate_duplicate_profiler_filename(document: dict[str, Any]) -> None:
        outputs = _find_fixture_test(document, "warmInboxWithBaselineProfile")[
            "profilerOutputs"
        ]
        outputs[1]["filename"] = outputs[0]["filename"]
        return None

    def memory_missing(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            "INSTRUMENTATION_STATUS: auroraPssMedianKiB=102400.5\n",
            "",
        )

    def memory_duplicate(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            "INSTRUMENTATION_STATUS: auroraPssMedianKiB=102400.5\n",
            "INSTRUMENTATION_STATUS: auroraPssMedianKiB=102400.5\n"
            "INSTRUMENTATION_STATUS: auroraPssMedianKiB=102401.0\n",
        )

    def memory_count(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            "INSTRUMENTATION_STATUS: auroraPssSampleCount=10",
            "INSTRUMENTATION_STATUS: auroraPssSampleCount=9",
        )

    def memory_raw_count(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            ",102800\nINSTRUMENTATION_STATUS: auroraPssMedianKiB",
            "\nINSTRUMENTATION_STATUS: auroraPssMedianKiB",
        )

    def memory_duplicate_raw(_: dict[str, Any]) -> str:
        raw_line = (
            "INSTRUMENTATION_STATUS: auroraPssSamplesKiB="
            "102000,102100,102200,102300,102400,102401,102500,102600,102700,102800\n"
        )
        return _passing_memory_text().replace(raw_line, raw_line + raw_line)

    def memory_zero_sample(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            "auroraPssSamplesKiB=102000,",
            "auroraPssSamplesKiB=0,",
        )

    def memory_tampered_median(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            "auroraPssMedianKiB=102400.5",
            "auroraPssMedianKiB=102401.0",
        )

    def memory_budget(_: dict[str, Any]) -> str:
        samples = ",".join([str(MAXIMUM_PSS_KIB_EXCLUSIVE)] * EXPECTED_MEMORY_SAMPLES)
        return _passing_memory_text().replace(
            "102000,102100,102200,102300,102400,102401,102500,102600,102700,102800",
            samples,
        ).replace(
            "auroraPssMedianKiB=102400.5",
            f"auroraPssMedianKiB={MAXIMUM_PSS_KIB_EXCLUSIVE}.0",
        )

    def memory_failed(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            "INSTRUMENTATION_CODE: -1",
            "FAILURES!!!\nINSTRUMENTATION_CODE: 0",
        )

    def memory_unfinished(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            "OK (1 test)\nINSTRUMENTATION_CODE: -1\n",
            "BUILD SUCCESSFUL\n",
        )

    def memory_wrong_class(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(
            MEMORY_CLASS_NAME,
            "org.aurorasms.macrobenchmark.OtherBenchmark",
        )

    def memory_wrong_test(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace(MEMORY_TEST_NAME, "otherTest")

    def memory_wrong_ok_count(_: dict[str, Any]) -> str:
        return _passing_memory_text().replace("OK (1 test)", "OK (2 tests)")

    def memory_duplicate_code(_: dict[str, Any]) -> str:
        return _passing_memory_text() + "INSTRUMENTATION_CODE: -1\n"

    rejection_cases.extend(
        (
            ("missing required test", mutate_missing_test, "exactly 10 benchmark records"),
            ("unexpected extra record", mutate_extra_record, "exactly 10 benchmark records"),
            ("duplicate expected record", mutate_duplicate_record, "duplicate expected"),
            ("wrong timing repeat count", mutate_repeat_count, "repeatIterations must be 30"),
            ("wrong warmup count", mutate_warmup_count, "warmupIterations must be 5"),
            ("wrong raw timing run count", mutate_timing_run_count, "has 29 iterations"),
            ("non-finite timing", mutate_non_finite_timing, "non-finite JSON number"),
            ("missing metric", mutate_missing_metric, "missing required metric"),
            ("ambiguous metric", mutate_ambiguous_metric, "ambiguous across metrics buckets"),
            ("timing P50 over budget", mutate_p50, "P50"),
            ("timing P95 over budget", mutate_p95, "P95"),
            ("one-percent frame misses", mutate_slow_frames, "is not below 1.000%"),
            ("frozen frame", mutate_frozen_frame, "at or above 700 ms"),
            ("misaligned frame metrics", mutate_frame_alignment, "mismatched sample counts"),
            ("wrong raw frame run count", mutate_frame_run_count, "has 9 iterations"),
            (
                "missing profiler output",
                mutate_missing_profiler_output,
                "profilerOutputs has 29 entries",
            ),
            ("tampered profiler type", mutate_profiler_type, "must be 'PerfettoTrace'"),
            (
                "tampered profiler filename",
                mutate_profiler_filename,
                "must end with '.perfetto-trace'",
            ),
            (
                "duplicate profiler filename",
                mutate_duplicate_profiler_filename,
                "duplicate/ambiguous Perfetto filename basename",
            ),
            ("missing memory value", memory_missing, "missing median PSS"),
            ("duplicate memory value", memory_duplicate, "duplicate/ambiguous median PSS"),
            ("wrong memory sample count", memory_count, "does not match 10 raw samples"),
            ("wrong raw memory sample count", memory_raw_count, "exactly 10 raw PSS"),
            (
                "duplicate raw memory field",
                memory_duplicate_raw,
                "duplicate/ambiguous raw PSS samples",
            ),
            ("non-positive raw memory sample", memory_zero_sample, "must all be positive"),
            ("tampered memory median", memory_tampered_median, "does not match"),
            ("memory at budget boundary", memory_budget, "is not below 150 MiB"),
            ("failed memory instrumentation", memory_failed, "reports failure token"),
            ("unfinished memory instrumentation", memory_unfinished, "exactly one 'OK"),
            ("wrong memory class", memory_wrong_class, "unexpected instrumentation class"),
            ("wrong memory test", memory_wrong_test, "unexpected instrumentation test"),
            ("wrong OK test count", memory_wrong_ok_count, "exactly one 'OK"),
            ("duplicate instrumentation code", memory_duplicate_code, "exactly one"),
        )
    )

    passed_cases = 0
    with tempfile.TemporaryDirectory(prefix="aurorasms-performance-verifier-") as raw_root:
        root = Path(raw_root)
        pass_dir, pass_memory = _write_fixture_case(root / "passing", copy.deepcopy(passing))
        (pass_dir / "runner-metadata.json").write_text(
            '{"mode": "full"}\n',
            encoding="utf-8",
        )
        report = verify_evidence((pass_dir,), (pass_memory,))
        interpolated_search = next(
            result
            for result in report.timing_results
            if result.test_name == "searchWithBaselineProfile"
        )
        if not math.isclose(
            interpolated_search.p95_ms,
            171.5,
            rel_tol=0.0,
            abs_tol=1e-9,
        ):
            raise AssertionError(
                "self-test did not use AndroidX p*(n-1) linear interpolation; "
                f"expected P95 171.5, got {interpolated_search.p95_ms}"
            )
        if report.perfetto_trace_count != EXPECTED_PERFETTO_TRACES:
            raise AssertionError("self-test passing trace count is not 260")
        passed_cases += 1

        duplicate_root = root / "duplicate-document"
        duplicate_dir, duplicate_memory = _write_fixture_case(
            duplicate_root, copy.deepcopy(passing)
        )
        (duplicate_dir / "second.json").write_text(
            json.dumps(passing, indent=2) + "\n", encoding="utf-8"
        )
        try:
            verify_evidence((duplicate_dir,), (duplicate_memory,))
        except EvidenceError as error:
            if "exactly one AndroidX Benchmark JSON document" not in str(error):
                raise AssertionError(
                    f"self-test duplicate document rejected for wrong reason: {error}"
                ) from error
        else:
            raise AssertionError("self-test duplicate AndroidX document was accepted")
        passed_cases += 1

        duplicate_key_root = root / "duplicate-json-key"
        duplicate_key_dir = duplicate_key_root / "benchmark"
        duplicate_key_dir.mkdir(parents=True)
        (duplicate_key_dir / "results.json").write_text(
            '{"benchmarks": [], "benchmarks": []}\n', encoding="utf-8"
        )
        duplicate_key_memory = duplicate_key_root / "memory.txt"
        duplicate_key_memory.write_text(_passing_memory_text(), encoding="utf-8")
        try:
            verify_evidence((duplicate_key_dir,), (duplicate_key_memory,))
        except EvidenceError as error:
            if "duplicate JSON object key" not in str(error):
                raise AssertionError(
                    f"self-test duplicate JSON key rejected for wrong reason: {error}"
                ) from error
        else:
            raise AssertionError("self-test duplicate JSON key was accepted")
        passed_cases += 1

        missing_trace_dir, missing_trace_memory = _write_fixture_case(
            root / "missing-trace-file",
            copy.deepcopy(passing),
        )
        missing_trace = next(missing_trace_dir.rglob("*.perfetto-trace"))
        missing_trace.unlink()
        try:
            verify_evidence((missing_trace_dir,), (missing_trace_memory,))
        except EvidenceError as error:
            if "resolves to 0 files" not in str(error):
                raise AssertionError(
                    f"self-test missing trace rejected for wrong reason: {error}"
                ) from error
        else:
            raise AssertionError("self-test missing trace file was accepted")
        passed_cases += 1

        unreferenced_dir, unreferenced_memory = _write_fixture_case(
            root / "unreferenced-trace-file",
            copy.deepcopy(passing),
        )
        (unreferenced_dir / "traces" / "orphan.perfetto-trace").write_bytes(b"orphan\n")
        try:
            verify_evidence((unreferenced_dir,), (unreferenced_memory,))
        except EvidenceError as error:
            if "unreferenced Perfetto trace" not in str(error):
                raise AssertionError(
                    f"self-test unreferenced trace rejected for wrong reason: {error}"
                ) from error
        else:
            raise AssertionError("self-test unreferenced trace file was accepted")
        passed_cases += 1

        ambiguous_dir, ambiguous_memory = _write_fixture_case(
            root / "ambiguous-trace-file",
            copy.deepcopy(passing),
        )
        original_trace = next(ambiguous_dir.rglob("*.perfetto-trace"))
        duplicate_trace_dir = ambiguous_dir / "duplicate"
        duplicate_trace_dir.mkdir()
        (duplicate_trace_dir / original_trace.name).write_bytes(b"duplicate\n")
        try:
            verify_evidence((ambiguous_dir,), (ambiguous_memory,))
        except EvidenceError as error:
            if "resolves to 2 files" not in str(error):
                raise AssertionError(
                    f"self-test ambiguous trace rejected for wrong reason: {error}"
                ) from error
        else:
            raise AssertionError("self-test ambiguous trace basename was accepted")
        passed_cases += 1

        for index, (label, mutate, expected_error) in enumerate(rejection_cases):
            document = copy.deepcopy(passing)
            memory_text = mutate(document)
            case_dir, case_memory = _write_fixture_case(
                root / f"reject-{index:02d}", document, memory_text
            )
            try:
                verify_evidence((case_dir,), (case_memory,))
            except EvidenceError as error:
                if expected_error not in str(error):
                    raise AssertionError(
                        f"self-test {label!r} rejected for wrong reason: {error}"
                    ) from error
            else:
                raise AssertionError(f"self-test {label!r} was accepted")
            passed_cases += 1

    print(
        "AuroraSMS performance verifier self-test: PASS "
        f"({passed_cases} cases: 1 acceptance, {passed_cases - 1} rejections; "
        "linear-interpolated percentiles and 260 trace bindings verified)"
    )


def _parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify a full AuroraSMS physical-device performance evidence set."
    )
    parser.add_argument(
        "--results",
        "--benchmark-input",
        "--benchmark-dir",
        "--benchmark-json",
        action="append",
        type=Path,
        dest="benchmark_inputs",
        metavar="PATH",
        help=(
            "AndroidX Benchmark JSON file or directory (recursive); repeat for "
            "multiple artifact directories"
        ),
    )
    parser.add_argument(
        "--memory-log",
        "--memory-output",
        action="append",
        type=Path,
        dest="memory_inputs",
        metavar="PATH",
        help="MemoryBenchmark instrumentation .txt output or directory; repeat as needed",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run generated passing and fail-closed fixture tests",
    )
    args = parser.parse_args(argv)
    if not args.self_test and (not args.benchmark_inputs or not args.memory_inputs):
        parser.error("--results and --memory-log are required unless --self-test is used")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv if argv is not None else sys.argv[1:])
    try:
        if args.self_test:
            _run_self_test()
            return 0
        report = verify_evidence(args.benchmark_inputs, args.memory_inputs)
    except (EvidenceError, AssertionError) as error:
        print("AuroraSMS full performance evidence: FAIL", file=sys.stderr)
        print(f"  {error}", file=sys.stderr)
        return 1
    print(_format_report(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
