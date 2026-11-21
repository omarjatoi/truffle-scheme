"""Test: the load rule (bench/README.md "Thresholds", plan.md M13-01). `bench-check --measure`
records the 1-minute load during each benchmark and its peak over the run (bench.py _LoadSampler),
`bench-check --update` leaves out a run whose peak passed 10 and says so in the thresholds' note
(_load_rule, _update_thresholds), and `bench-report` gives the load during each program's runs next
to its times (_write_report_markdown)."""

import contextlib
import io
import json
import os
import shutil
import tempfile
import threading
import time
import unittest

import bench


class FakeLoad:
    """A load average that reads `values` one after another, then the last one again."""

    def __init__(self, values):
        self.values = list(values)
        self.reads = 0
        self.lock = threading.Lock()

    def __call__(self):
        with self.lock:
            value = self.values[min(self.reads, len(self.values) - 1)]
            self.reads += 1
            return value

    def wait_for(self, reads):
        deadline = time.monotonic() + 10
        while self.reads < reads and time.monotonic() < deadline:
            time.sleep(0.005)


class LoadSampler(unittest.TestCase):

    def test_peak_during_the_block(self):
        # The peak is read neither at the start nor at the end, only while the block runs: what
        # reading the load at a run's start and end, as before M13-01, missed.
        load = FakeLoad([2.0, 11.5, 3.0, 4.0])
        with bench._LoadSampler(every=0.001, read=load) as sampler:
            load.wait_for(4)
        self.assertEqual(sampler.summary(), {'start': 2.0, 'min': 2.0, 'max': 11.5, 'end': 4.0})

    def test_a_short_block_reads_its_start_and_end(self):
        load = FakeLoad([5.123, 6.0])
        with bench._LoadSampler(every=3600, read=load) as sampler:
            pass
        self.assertEqual(sampler.summary(), {'start': 5.12, 'min': 5.12, 'max': 6.0, 'end': 6.0})

    def test_merged(self):
        self.assertEqual(bench._merged_load([{'start': 3, 'min': 2, 'max': 4, 'end': 3.5},
                                             {'start': 3.5, 'min': 3.5, 'max': 12, 'end': 6}]),
                         {'start': 3, 'min': 2, 'max': 12, 'end': 6})
        self.assertIsNone(bench._merged_load([]))


def fib_case():
    # A case whose workload needs no input file (the vendored set isn't in the test's runfiles).
    return next(case for case in bench._CHECK_CASES if case.key == 'fib')


def run(metric_ms, peak):
    case = fib_case()
    result = {'metric_ms': metric_ms, 'samples_ms': [metric_ms], 'workload': case.workload()}
    run = {'host': 'Darwin 27.0.0 (arm64), test', 'platform': 'darwin-arm64', 'commit': 'abc', 'date': '2026-11-21 10:00 UTC',
           'results': {'fib': result}}
    if peak is not None:
        result['load'] = {'start': 1.0, 'min': 1.0, 'max': peak, 'end': 2.0}
        run['load'] = dict(result['load'])
    return run


class LoadRule(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.saved = bench._THRESHOLDS
        bench._THRESHOLDS = os.path.join(self.dir, 'thresholds.json')

    def tearDown(self):
        bench._THRESHOLDS = self.saved
        shutil.rmtree(self.dir)

    def update(self, runs, note='12 runs here'):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            bench._update_thresholds(runs, [fib_case()], note, [f'm{i + 1}.json' for i in range(len(runs))])
        with open(bench._THRESHOLDS, encoding='utf-8') as f:
            return json.load(f), out.getvalue()

    def test_a_run_past_10_is_left_out(self):
        thresholds, out = self.update([run(100.0, 6.2), run(250.0, 10.5), run(102.0, 9.9)])
        self.assertEqual(thresholds['benchmarks']['fib']['runs_ms'], [100.0, 102.0])
        self.assertEqual(thresholds['measured']['runs'], 2)
        note = thresholds['measured']['note']
        self.assertTrue(note.startswith('12 runs here. 1 of 3 runs left out by the load rule'), note)
        self.assertIn('peaks 10.5', note)
        self.assertIn('the 2 runs used peaked at 6.2-9.9', note)
        self.assertIn('left out m2.json', out)

    def test_none_left_out(self):
        thresholds, _ = self.update([run(100.0, 3.0), run(101.0, 10.0)], note='')
        self.assertEqual(thresholds['benchmarks']['fib']['runs_ms'], [100.0, 101.0])
        self.assertTrue(thresholds['measured']['note'].startswith('No run left out by the load rule'))

    def test_a_run_without_a_load_is_kept_and_noted(self):
        thresholds, _ = self.update([run(100.0, None), run(101.0, 4.0)])
        self.assertEqual(thresholds['benchmarks']['fib']['runs_ms'], [100.0, 101.0])
        self.assertIn('1 run(s) recorded no load', thresholds['measured']['note'])

    def test_every_run_past_10_fails(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as err:
            with self.assertRaises(SystemExit):
                bench._update_thresholds([run(100.0, 11.0)], [fib_case()], '')
        self.assertIn('every run passed a 1-minute load of 10', err.getvalue())
        self.assertFalse(os.path.exists(bench._THRESHOLDS))


def report(loads):
    """A report of one program on our JIT, with or without the load during each run."""
    programs = {}
    for program, load in loads.items():
        result = {'status': 'ok', 'times': [1.0, 0.5, 0.5], 'count': 1, 'seconds': 1.0, 'steady_seconds': 0.5, 'rss_mb': 900.0}
        programs[program] = {'count': 1, 'program_count': 10, 'results': {'jit': result}}
        if load:
            result['load'] = load
            programs[program]['load'] = load
    return {'generated': '2026-11-21 10:00 UTC', 'command': 'bench-report', 'host': 'test', 'note': '', 'commit': 'abc',
            'scale': 0.1, 'interpreter_scale': 0.1, 'jit_iterations': 3, 'timeout': 300, 'minutes': 1.0,
            'load': {'start': 3.0, 'max': 12.25, 'end': 4.0},
            'implementations': {'jit': {'title': 'Ours, JIT', 'version': 'test', 'command': 'java', 'notes': []}},
            'programs': programs}


class ReportLoad(unittest.TestCase):

    def render(self, report):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'benchmarks.md')
            bench._write_report_markdown(report, path)
            with open(path, encoding='utf-8') as f:
                return f.read()

    def test_the_load_next_to_each_program(self):
        text = self.render(report({'fib': {'start': 3.0, 'min': 3.0, 'max': 7.5, 'end': 4.0},
                                   'tak': {'start': 9.0, 'min': 8.5, 'max': 12.25, 'end': 11.0}}))
        self.assertIn('| Program | Count | Ours, JIT | Ours, JIT steady | 1-minute load |', text)
        self.assertIn('| fib | 1 | **1.00 (1.0)** | 0.50 (0.5) | 3–7.5 |', text)
        # A run during which the load passed 10 is marked.
        self.assertIn('| tak | 1 | **1.00‡ (1.0)** | 0.50‡ (0.5) | 8.5–12.25 |', text)
        self.assertIn('at most 12.25 during a run, 4 at the end. Read every 1 s during each run', text)

    def test_a_report_from_before(self):
        # A report from before M13-01 (docs/benchmarks.json, `--render-only`) renders as it did.
        text = self.render(report({'fib': None}))
        self.assertIn('| Program | Count | Ours, JIT | Ours, JIT steady |\n', text)
        self.assertIn('| fib | 1 | **1.00 (1.0)** | 0.50 (0.5) |\n', text)
        self.assertIn('at most 12.25 before a program, 4 at the end.\n', text)


if __name__ == '__main__':
    unittest.main()
