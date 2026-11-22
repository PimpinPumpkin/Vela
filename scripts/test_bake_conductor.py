"""Tests for the places slice rotation in bake-conductor.py: python3 scripts/test_bake_conductor.py"""
import calendar
import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location(
    "bake_conductor", os.path.join(os.path.dirname(os.path.abspath(__file__)), "bake-conductor.py"))
bc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bc)

JOB = {"id": "places-slice", "inputs": {"slice": "@slice7"}, "everyHours": 24}
DAY = bc.DAY
MONDAY_9 = calendar.timegm((2026, 11, 16, 9, 0, 0))  # a Monday, 09:00 UTC (weekday 0)


class TestSlices(unittest.TestCase):
    def test_old_record_dispatched_today_waits_for_tomorrow(self):
        st = {"lastSuccess": MONDAY_9 - 3600, "dispatchedAt": MONDAY_9 - 7200}
        self.assertIsNone(bc.due_slice(JOB, st, MONDAY_9))
        nxt = bc.due_slice(JOB, st, MONDAY_9 + DAY)
        self.assertEqual(nxt[0], 1)

    def test_old_record_not_dispatched_today_runs_today(self):
        st = {"lastSuccess": MONDAY_9 - DAY, "dispatchedAt": MONDAY_9 - 30 * 3600}
        self.assertEqual(bc.due_slice(JOB, st, MONDAY_9)[0], 0)

    def test_a_late_start_keeps_the_weekday(self):
        # Every bake starts five hours later than the last; each day still bakes that day's slice.
        st = {"dispatchedAt": MONDAY_9}
        bc.slice_ages(st, MONDAY_9)
        t = MONDAY_9
        for day in range(1, 15):
            t = MONDAY_9 + day * DAY + (day * 5 * 3600) % (12 * 3600)
            ds = bc.due_slice(JOB, st, t)
            self.assertIsNotNone(ds, day)
            self.assertEqual(ds[0], bc.time.gmtime(t).tm_wday, day)
            st["slices"][str(ds[0])] = t
            self.assertIsNone(bc.due_slice(JOB, st, t + 3600), day)

    def test_a_missed_day_is_caught_up(self):
        st = {"dispatchedAt": MONDAY_9}
        bc.slice_ages(st, MONDAY_9)
        # Nothing ran on Tuesday. Wednesday bakes its own slice first, then Tuesday's.
        wed = MONDAY_9 + 2 * DAY
        first = bc.due_slice(JOB, st, wed)
        self.assertEqual(first[0], 2)
        st["slices"]["2"] = wed
        second = bc.due_slice(JOB, st, wed + 4 * 3600)
        self.assertEqual(second[0], 1)
        st["slices"]["1"] = wed + 4 * 3600
        self.assertIsNone(bc.due_slice(JOB, st, wed + 8 * 3600))

    def test_other_jobs_are_not_slices(self):
        self.assertFalse(bc.slice_job({"inputs": {"only": "x"}}))
        self.assertTrue(bc.slice_job(JOB))


if __name__ == "__main__":
    unittest.main()
