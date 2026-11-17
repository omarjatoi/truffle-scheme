"""Test: bench-report reads the native launcher's -XX:+PrintGC lines, its serial collector's and
G1's (bench.py _heap_from_native_gc_lines), and leaves them out of the program's output.

The samples are the standard error of GraalVM 25.5's native launcher (`bazel-bin/scheme-native
-XX:+PrintGC -Xmx160m` before M10-01, `--vm.XX:+PrintGC --vm.Xmx160m` since, which write the same
lines, on a program that keeps a few lists of 100,000 vectors), built with the default collector
and with `--//:native_graalvm=oracle --//:native_gc=G1`."""

import unittest

import bench

SERIAL = '''\
[0.063s] GC(0) Pause Incremental GC (Collect on allocation) 40.00M->30.00M 20.102ms
[0.082s] GC(1) Pause Incremental GC (Collect on allocation) 48.00M->45.00M 11.525ms
[0.271s] GC(8) Pause Full GC (Collect on allocation) 108.00M->83.50M 79.574ms
[0.291s] GC(10) Pause Incremental GC (Collect on allocation) 114.50M->100.00M 8.106ms
'''

G1 = '''\
[0.000s][info][gc] Using G1
[0.051s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 78M->60M(304M) 8.148ms
[0.120s][info][gc] GC(4) Pause Young (Concurrent Start) (G1 Evacuation Pause) 222M->178M(912M) 7.632ms
[0.120s][info][gc] GC(5) Concurrent Mark Cycle
[0.133s][info][gc] GC(5) Pause Remark 272M->259M(560M) 0.251ms
[0.139s][info][gc] GC(5) Pause Cleanup 303M->303M(560M) 0.013ms
[0.139s][info][gc] GC(5) Concurrent Mark Cycle 19.874ms
[0.177s][info][gc] GC(17) Pause Full (G1 Compaction Pause) 194M->121M(200M) 22.475ms
'''


class NativeGcLines(unittest.TestCase):

    def test_serial(self):
        self.assertEqual(bench._heap_from_native_gc_lines(SERIAL),
                         {'heap_mb': 100.0, 'committed_mb': None, 'gcs': 4, 'gc_ms': 119})

    def test_g1(self):
        # Every pause, Remark and Cleanup included, as the JVM's log is read (_GC_PAUSE_LINE); not
        # the concurrent cycle, which isn't one.
        self.assertEqual(bench._heap_from_native_gc_lines(G1),
                         {'heap_mb': 303.0, 'committed_mb': 912.0, 'gcs': 5, 'gc_ms': 39})

    def test_kilobytes_and_gigabytes(self):
        lines = ('[1.5s] GC(0) Pause Incremental GC (Collect on allocation) 512.00K->256.00K 1.000ms\n'
                 '[2.5s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 3G->2G(4G) 2.000ms\n')
        self.assertEqual(bench._heap_from_native_gc_lines(lines),
                         {'heap_mb': 2048.0, 'committed_mb': 4096.0, 'gcs': 2, 'gc_ms': 3})

    def test_no_collection(self):
        self.assertEqual(bench._heap_from_native_gc_lines('[0.000s][info][gc] Using G1\n'),
                         {'heap_mb': None, 'committed_mb': None, 'gcs': 0, 'gc_ms': 0})

    def test_the_program_output_keeps_the_rest(self):
        for lines in (SERIAL, G1):
            text = 'error: before\n' + lines + 'Elapsed time: 1.0 seconds (1) for fib:30\n'
            self.assertEqual(bench._without_native_gc_lines(text), 'error: before\nElapsed time: 1.0 seconds (1) for fib:30\n')
        # A line of the program's own is kept, even one that mentions a collection.
        self.assertEqual(bench._without_native_gc_lines('GC(0) Pause 1M->1M 1ms\n'), 'GC(0) Pause 1M->1M 1ms\n')


if __name__ == '__main__':
    unittest.main()
