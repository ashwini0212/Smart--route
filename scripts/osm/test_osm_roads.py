"""Unit tests for osm_roads.py. Run: python3 -m unittest discover -s scripts/osm"""
import os
import tempfile
import unittest

import osm_roads


def fixture():
    # A tiny hand-made Overpass response: a two-way residential street, a one-way primary road,
    # a reversed one-way, a footway (not drivable) and a way referencing a node missing from the extract.
    return {
        "elements": [
            {"type": "node", "id": 1, "lat": 12.9700, "lon": 77.5900},
            {"type": "node", "id": 2, "lat": 12.9710, "lon": 77.5900},
            {"type": "node", "id": 3, "lat": 12.9710, "lon": 77.5910},
            {"type": "node", "id": 4, "lat": 12.9720, "lon": 77.5910},
            {"type": "way", "id": 10, "nodes": [1, 2], "tags": {"highway": "residential"}},
            {"type": "way", "id": 11, "nodes": [2, 3], "tags": {"highway": "primary", "oneway": "yes", "maxspeed": "40"}},
            {"type": "way", "id": 12, "nodes": [3, 4], "tags": {"highway": "tertiary", "oneway": "-1"}},
            {"type": "way", "id": 13, "nodes": [1, 4], "tags": {"highway": "footway"}},
            {"type": "way", "id": 14, "nodes": [4, 99], "tags": {"highway": "residential"}},
        ]
    }


class ConvertTest(unittest.TestCase):

    def setUp(self):
        self.nodes, self.edges = osm_roads.convert(fixture())

    def test_only_nodes_on_drivable_ways_become_graph_nodes(self):
        self.assertEqual([n[1] for n in self.nodes], [1, 2, 3, 4])
        self.assertEqual([n[0] for n in self.nodes], [0, 1, 2, 3])

    def test_two_way_street_produces_both_directions(self):
        pairs = {(e[0], e[1]) for e in self.edges}
        self.assertIn((0, 1), pairs)
        self.assertIn((1, 0), pairs)

    def test_oneway_yes_produces_forward_only(self):
        pairs = {(e[0], e[1]) for e in self.edges}
        self.assertIn((1, 2), pairs)
        self.assertNotIn((2, 1), pairs)

    def test_oneway_minus_one_produces_reverse_only(self):
        pairs = {(e[0], e[1]) for e in self.edges}
        self.assertIn((3, 2), pairs)
        self.assertNotIn((2, 3), pairs)

    def test_footway_and_missing_nodes_are_ignored(self):
        self.assertEqual(len(self.edges), 4)

    def test_distance_and_maxspeed(self):
        primary = next(e for e in self.edges if e[4] == "primary")
        # 0.001 degrees of longitude at latitude 12.97 is about 108.4 m.
        self.assertAlmostEqual(primary[2], 108.4, delta=0.5)
        self.assertAlmostEqual(primary[3], primary[2] / (40 / 3.6), delta=0.02)

    def test_default_speed_when_no_maxspeed(self):
        residential = next(e for e in self.edges if e[4] == "residential")
        self.assertAlmostEqual(residential[3], residential[2] / (20 / 3.6), delta=0.02)


class HelpersTest(unittest.TestCase):

    def test_parse_maxspeed(self):
        self.assertEqual(osm_roads.parse_maxspeed("50"), 50.0)
        self.assertEqual(osm_roads.parse_maxspeed("50 km/h"), 50.0)
        self.assertAlmostEqual(osm_roads.parse_maxspeed("30 mph"), 48.28, places=2)
        self.assertIsNone(osm_roads.parse_maxspeed("none"))
        self.assertIsNone(osm_roads.parse_maxspeed(None))

    def test_roundabout_is_one_way(self):
        self.assertEqual(osm_roads.direction({"highway": "primary", "junction": "roundabout"}), (True, False))

    def test_write_csv_round_trip(self):
        with tempfile.TemporaryDirectory() as out:
            nodes, edges = osm_roads.convert(fixture())
            osm_roads.write_csv(out, nodes, edges)
            with open(os.path.join(out, "edges.csv")) as f:
                lines = f.read().splitlines()
            self.assertEqual(lines[0], "from_id,to_id,distance_m,travel_time_s,road_class")
            self.assertEqual(len(lines), 1 + len(edges))


if __name__ == "__main__":
    unittest.main()
