"""Syntetický jízdní řád ve stylu IDS JMK.

Trasa linky 1 (spoj T1):
    Mendlovo nám. U300Z1 08:00 → Česká U100Z1 08:05/08:06 → Hlavní nádraží U200Z1 08:10 → Konečná U400Z1 08:15
Linka 6 (T2):  Česká U100Z2 08:20 → Hlavní nádraží U200Z1 08:25
Noční N1 (T3): Česká U100Z1 24:10 → Hlavní nádraží U200Z1 24:15   (patří do předchozího dne provozu)
T4: jako T2, ale služba je 27. 10. 2026 zrušená přes calendar_dates
"""

from __future__ import annotations

import io
import zipfile
from datetime import datetime

import pytest

from dpmb_server.gtfs import load_timetable
from dpmb_server.timeutil import TZ

FILES = {
    "routes.txt": """route_id,route_short_name,route_long_name,route_type
L1D1,1,,0
L6D1,6,,0
L99D1,N1,,3
""",
    "calendar.txt": """service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
ALL,1,1,1,1,1,1,1,20260101,20271231
""",
    "calendar_dates.txt": """service_id,date,exception_type
SOMETIMES,20261028,1
SOMETIMES,20261027,2
""",
    "trips.txt": """route_id,service_id,trip_id,trip_headsign
L1D1,ALL,T1,Konečná
L6D1,ALL,T2,Hlavní nádraží
L99D1,ALL,T3,
L6D1,SOMETIMES,T4,Zrušený
""",
    "stop_times.txt": """trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
T1,08:00:00,08:00:00,U300Z1,1,0
T1,08:05:00,08:06:00,U100Z1,2,0
T1,08:10:00,08:10:00,U200Z1,3,0
T1,08:15:00,08:15:00,U400Z1,4,1
T2,08:20:00,08:20:00,U100Z2,1,0
T2,08:25:00,08:25:00,U200Z1,2,0
T3,24:10:00,24:10:00,U100Z1,1,0
T3,24:15:00,24:15:00,U200Z1,2,0
T4,08:20:00,08:20:00,U100Z2,1,0
T4,08:25:00,08:25:00,U200Z1,2,0
""",
    "stops.txt": """stop_id,stop_name,stop_lat,stop_lon,location_type
U300Z1,Mendlovo náměstí,49.1910,16.5930,0
U100Z1,Česká,49.1960,16.6060,0
U100Z2,Česká,49.1962,16.6064,0
U200Z1,Hlavní nádraží,49.1900,16.6110,0
U400Z1,Konečná,49.1850,16.6200,0
U999,Nepoužitá,49.0,16.0,0
""",
}


def build_zip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, content in FILES.items():
            zf.writestr(name, content)
    return buf.getvalue()


def local(y: int, mo: int, d: int, h: int, mi: int, s: int = 0) -> float:
    return datetime(y, mo, d, h, mi, s, tzinfo=TZ).timestamp()


@pytest.fixture(scope="session")
def gtfs_bytes() -> bytes:
    return build_zip()


@pytest.fixture(scope="session")
def tt(gtfs_bytes):
    return load_timetable(gtfs_bytes, version="test")
