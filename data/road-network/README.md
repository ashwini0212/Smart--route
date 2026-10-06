# Road network data

SmartRoute routes on a directed road graph stored as two CSV files:

```
nodes.csv  id,osm_id,lat,lon
edges.csv  from_id,to_id,distance_m,travel_time_s,road_class
```

## Real data: OpenStreetMap extract

```bash
python3 scripts/osm/osm_roads.py --bbox 12.955,77.575,12.995,77.625 --out data/road-network/bengaluru-central
```

The bounding box above is roughly 4.4 km × 5.4 km of central Bengaluru (MG Road, Shivajinagar, Richmond Town). The script needs internet access to `overpass-api.de`.

> **Status:** not committed yet. The cloud environment this project is built in blocks `overpass-api.de`, so the extract has not been downloaded. Run the command above on a machine with internet access and commit the two CSV files.

Map data © OpenStreetMap contributors, licensed under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/). Any screenshot or deployment using this data must show that attribution.

Travel times are **free-flow estimates** derived from road class (or the `maxspeed` tag), not measured traffic.

## Synthetic data (default)

When no CSV directory is configured, SmartRoute generates a deterministic synthetic city (a jittered grid around central Bengaluru with arterials, one-way and missing streets) using `CityGraphGenerator`. It is labelled as synthetic everywhere it is shown. Its streets do not match the real streets on a map.

## Loading rules

1. Read CSV (`RoadNetworkCsv`); malformed rows fail with file and line number.
2. Keep only the largest strongly connected component (`LargestComponent`), so every pair of points has a route in both directions. The number of removed nodes is logged.
3. Build the k-d tree (`NearestNodeIndex`) to snap addresses and GPS points to the nearest node.
