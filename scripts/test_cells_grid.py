"""Grid clipping tests using Davis/Sacramento region coordinates."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cells_grid as cg  # noqa: E402

RING = [(-121.8, 38.5), (-121.5, 38.5), (-121.5, 38.8), (-121.8, 38.8), (-121.8, 38.5)]


def area2(ring):
    x, y = ring[0]
    return sum((a[0] - x) * (b[1] - y) - (b[0] - x) * (a[1] - y)
               for a, b in zip(ring, ring[1:] + ring[:1]))


class TestClip(unittest.TestCase):
    def variants(self):
        for ring in (RING, RING[:-1]):
            yield ring
            yield list(reversed(ring))

    def test_zero_area_edge_touch(self):
        for ring in self.variants():
            with self.subTest(ring=ring):
                self.assertIsNone(cg.clip(ring, -121.5, 38.5, -121.0, 39.0))

    def test_zero_area_corner_touch(self):
        for ring in self.variants():
            with self.subTest(ring=ring):
                self.assertIsNone(cg.clip(ring, -121.5, 38.8, -121.0, 39.0))

    def test_positive_intersection(self):
        for ring in self.variants():
            with self.subTest(ring=ring):
                result = cg.clip(ring, -122.0, 38.5, -121.5, 39.0)
                self.assertIsNotNone(result)
                self.assertAlmostEqual(area2(result), area2(ring))

    def test_small_positive_intersection(self):
        for ring in self.variants():
            with self.subTest(ring=ring):
                result = cg.clip(ring, -121.5 - 1e-8, 38.8 - 1e-8, -121.0, 39.0)
                self.assertIsNotNone(result)
                self.assertGreater(abs(area2(result)), 0)
                self.assertGreater(area2(result) * area2(ring), 0)

    def test_disjoint(self):
        self.assertIsNone(cg.clip(RING, -121.4, 38.5, -121.0, 39.0))


class TestGridCommand(unittest.TestCase):
    def run_grid(self, rings):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "polys.json")
            polygons = {} if rings is None else {"fixture": {"o": [
                [v for lng, lat in ring for v in (lat, lng)] for ring in rings]}}
            with open(source, "w") as f:
                json.dump(polygons, f)
            result = subprocess.run(
                [sys.executable, cg.__file__, "fixture", "[38.5,-122.0,39.0,-121.0]",
                 source, directory], check=True, capture_output=True, text=True)
            artifacts = {}
            for name in os.listdir(directory):
                if name.endswith(".geojson"):
                    with open(os.path.join(directory, name)) as f:
                        artifacts[name] = json.load(f)
            return [line.split("\t") for line in result.stdout.splitlines()], artifacts

    def test_omit_zero_area_cells_and_artifacts(self):
        rows, artifacts = self.run_grid([RING])
        self.assertEqual([row[0] for row in rows], ["fixture.n38.5w122.0"])
        self.assertEqual(rows[0][-1], "region")
        self.assertEqual(set(artifacts), {"fixture.n38.5w122.0.geojson"})

    def test_multipart_keeps_only_positive_parts(self):
        other = [(-121.4, 38.6), (-121.3, 38.6), (-121.3, 38.7), (-121.4, 38.7)]
        rows, artifacts = self.run_grid([RING, other])
        self.assertEqual(len(rows), 2)
        self.assertEqual(len(artifacts), 2)
        for feature in artifacts.values():
            geometry = feature["geometry"]
            self.assertEqual(geometry["type"], "MultiPolygon")
            self.assertEqual(len(geometry["coordinates"]), 1)
            ring = geometry["coordinates"][0][0]
            self.assertEqual(ring[0], ring[-1])
            self.assertGreater(abs(area2(ring)), 0)

    def test_bbox_without_region_polygons(self):
        for rings in (None, []):
            with self.subTest(rings=rings):
                rows, artifacts = self.run_grid(rings)
                self.assertEqual(len(rows), 2)
                self.assertTrue(all(row[-1] == "bbox" for row in rows))
                self.assertEqual(artifacts, {})


if __name__ == "__main__":
    unittest.main()
