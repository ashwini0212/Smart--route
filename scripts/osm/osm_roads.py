#!/usr/bin/env python3
"""
Download drivable roads for a bounding box from OpenStreetMap (Overpass API) and convert them
to SmartRoute's road-network CSV format.

Usage:
    python3 scripts/osm/osm_roads.py --bbox 12.955,77.575,12.995,77.625 --out data/road-network/bengaluru-central
    python3 scripts/osm/osm_roads.py --input overpass.json --out data/road-network/bengaluru-central

Output (both files have a header row):
    nodes.csv  id,osm_id,lat,lon
    edges.csv  from_id,to_id,distance_m,travel_time_s,road_class

Notes:
- Data (c) OpenStreetMap contributors, available under the Open Database License (ODbL).
  Anything built from it must show that attribution.
- Every OSM node on a drivable way becomes a graph node (no contraction), so routes follow the
  real street shape on the map.
- Speeds are free-flow estimates per road class (or the way's maxspeed tag). They are assumptions,
  not measured traffic.
- Standard library only, so it runs anywhere Python 3.9+ is installed.
"""
import argparse
import csv
import json
import math
import os
import sys
import urllib.parse
import urllib.request

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
EARTH_RADIUS_M = 6_371_008.8

# Free-flow speed assumptions (km/h) for urban Indian roads by OSM highway class.
DEFAULT_SPEED_KMH = {
    "motorway": 60, "motorway_link": 40,
    "trunk": 45, "trunk_link": 35,
    "primary": 35, "primary_link": 30,
    "secondary": 30, "secondary_link": 25,
    "tertiary": 25, "tertiary_link": 20,
    "unclassified": 20, "residential": 20,
    "living_street": 10, "service": 15,
}
DRIVABLE = set(DEFAULT_SPEED_KMH)


def overpass_query(south, west, north, east):
    return (
        "[out:json][timeout:120];"
        f'way["highway"~"^({"|".join(sorted(DRIVABLE))})$"]'
        '["access"!~"^(no|private)$"]["motor_vehicle"!~"^(no|private)$"]'
        f"({south},{west},{north},{east});"
        "(._;>;);out body;"
    )


def download(bbox):
    data = urllib.parse.urlencode({"data": overpass_query(*bbox)}).encode()
    request = urllib.request.Request(OVERPASS_URL, data=data, headers={"User-Agent": "smartroute-osm-import/1.0"})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)


def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def parse_maxspeed(value):
    """'40', '40 km/h' -> 40.0; 'none', '30 mph' etc. -> None (mph converted)."""
    if not value:
        return None
    text = value.strip().lower()
    try:
        if text.endswith("mph"):
            return float(text[:-3].strip()) * 1.609344
        return float(text.replace("km/h", "").strip())
    except ValueError:
        return None


def direction(tags):
    """Returns (forward, backward) booleans for a way."""
    oneway = tags.get("oneway", "").lower()
    if oneway in ("yes", "true", "1"):
        return True, False
    if oneway == "-1":
        return False, True
    if oneway == "no":
        return True, True
    if tags.get("junction") in ("roundabout", "circular") or tags.get("highway") == "motorway":
        return True, False
    return True, True


def convert(overpass_json):
    """Converts Overpass JSON into (nodes, edges) lists ready to write as CSV."""
    elements = overpass_json.get("elements", [])
    coords = {e["id"]: (e["lat"], e["lon"]) for e in elements if e.get("type") == "node"}
    ways = [e for e in elements if e.get("type") == "way" and e.get("tags", {}).get("highway") in DRIVABLE]

    index = {}
    nodes = []

    def node_index(osm_id):
        if osm_id not in index:
            lat, lon = coords[osm_id]
            index[osm_id] = len(nodes)
            nodes.append((len(nodes), osm_id, lat, lon))
        return index[osm_id]

    edges = []
    for way in ways:
        tags = way.get("tags", {})
        road_class = tags["highway"]
        speed_kmh = parse_maxspeed(tags.get("maxspeed")) or DEFAULT_SPEED_KMH[road_class]
        forward, backward = direction(tags)
        refs = [r for r in way.get("nodes", []) if r in coords]
        for a, b in zip(refs, refs[1:]):
            if a == b:
                continue
            distance = haversine_m(*coords[a], *coords[b])
            seconds = distance / (speed_kmh / 3.6)
            ia, ib = node_index(a), node_index(b)
            if forward:
                edges.append((ia, ib, round(distance, 2), round(seconds, 2), road_class))
            if backward:
                edges.append((ib, ia, round(distance, 2), round(seconds, 2), road_class))
    return nodes, edges


def write_csv(out_dir, nodes, edges):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "nodes.csv"), "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "osm_id", "lat", "lon"])
        writer.writerows(nodes)
    with open(os.path.join(out_dir, "edges.csv"), "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["from_id", "to_id", "distance_m", "travel_time_s", "road_class"])
        writer.writerows(edges)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--bbox", help="south,west,north,east in degrees")
    source.add_argument("--input", help="previously downloaded Overpass JSON file")
    parser.add_argument("--out", required=True, help="output directory")
    args = parser.parse_args(argv)

    if args.input:
        with open(args.input) as f:
            data = json.load(f)
    else:
        bbox = [float(x) for x in args.bbox.split(",")]
        if len(bbox) != 4:
            parser.error("--bbox needs four numbers: south,west,north,east")
        data = download(bbox)

    nodes, edges = convert(data)
    write_csv(args.out, nodes, edges)
    print(f"wrote {len(nodes)} nodes and {len(edges)} directed edges to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
