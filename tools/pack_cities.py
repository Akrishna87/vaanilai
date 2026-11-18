"""Build the offline wind-map city asset from Natural Earth's public-domain places.

Run from the repository root:
  curl -fLO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_50m_populated_places.geojson
  python3 tools/pack_cities.py ne_50m_populated_places.geojson app/src/main/assets/cities.tsv
"""

import json
import sys
from pathlib import Path


def pack(source, destination):
    places = []
    for feature in json.loads(source.read_text(encoding="utf-8"))["features"]:
        props = feature["properties"]
        lon, lat = feature["geometry"]["coordinates"]
        name = props["NAME"]
        rank = props["SCALERANK"]
        priority = (-props["ADM0CAP"], -props["POP_MAX"], rank, name, lat, lon)
        places.append((priority, f"{rank}\t{lat:.4f}\t{lon:.4f}\t{name}\n"))
    with destination.open("w", encoding="utf-8", newline="\n") as out:
        out.write("# Natural Earth 1:50m populated places | rank, latitude, longitude, name\n")
        out.writelines(row for _, row in sorted(places))


if __name__ == "__main__":
    pack(*map(Path, sys.argv[1:]))
