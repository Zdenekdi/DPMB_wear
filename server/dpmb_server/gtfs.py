"""Načtení statického GTFS IDS JMK do kompaktních polí.

Jízdní řád celého kraje má miliony řádků stop_times. Jako pythonní objekty by
zabral gigabajty, proto jde všechno do numpy polí a dotazy jsou vektorové.

Zastávky IDS JMK mají tvar U<číslo>Z<nástupiště> (např. U1472Z2). Hodinky
pracují se skupinou U1472 – uživatele zajímá „Česká“, ne konkrétní sloupek.
"""

from __future__ import annotations

import csv
import io
import re
import zipfile
from array import array
from dataclasses import dataclass, field
from datetime import date
from typing import Iterator

import numpy as np

from .timeutil import gtfs_date

_STOP_RE = re.compile(r"^(U\d+)Z(\d+)$")
_RT_STOP_RE = re.compile(r"^U0*(\d+)Z0*(\d+)$")


def normalize_stop_id(stop_id: str) -> str:
    """Realtime feed posílá ID s nulami navíc: U01472Z02 → U1472Z2."""
    m = _RT_STOP_RE.match(stop_id)
    return f"U{m.group(1)}Z{m.group(2)}" if m else stop_id


def group_key(stop_id: str) -> str:
    m = _STOP_RE.match(stop_id)
    return m.group(1) if m else stop_id


def _parse_time(value: str) -> int:
    h, m, *s = value.split(":")
    return int(h) * 3600 + int(m) * 60 + (int(s[0]) if s else 0)


def _rows(zf: zipfile.ZipFile, name: str) -> Iterator[dict[str, str]]:
    with zf.open(name) as raw:
        text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
        reader = csv.reader(text)
        header = [h.strip() for h in next(reader)]
        for row in reader:
            if row:
                yield dict(zip(header, row))


@dataclass
class Service:
    weekdays: int = 0  # bit 0 = pondělí
    start: int = 0
    end: int = 0
    added: set[int] = field(default_factory=set)
    removed: set[int] = field(default_factory=set)

    def active(self, day: date) -> bool:
        d = gtfs_date(day)
        if d in self.removed:
            return False
        if d in self.added:
            return True
        return self.start <= d <= self.end and bool(self.weekdays & (1 << day.weekday()))


class Timetable:
    """Statický jízdní řád připravený na dotazy „odjezdy ze skupiny zastávek“."""

    def __init__(self, zf: zipfile.ZipFile, version: str | None = None):
        self.version = version
        self._load_routes(zf)
        self._load_services(zf)
        self._load_trips(zf)
        self._load_stop_times(zf)
        self._load_stops(zf)
        self._build_departure_index()

    # ---------- načtení ----------

    def _load_routes(self, zf: zipfile.ZipFile) -> None:
        self.route_ids: list[str] = []
        self.route_short: list[str] = []
        self.route_index: dict[str, int] = {}
        for r in _rows(zf, "routes.txt"):
            self.route_index[r["route_id"]] = len(self.route_ids)
            self.route_ids.append(r["route_id"])
            self.route_short.append(r.get("route_short_name") or r.get("route_long_name") or "")

    def _load_services(self, zf: zipfile.ZipFile) -> None:
        self.services: list[Service] = []
        self.service_index: dict[str, int] = {}
        names = set(zf.namelist())
        cols = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
        if "calendar.txt" in names:
            for r in _rows(zf, "calendar.txt"):
                s = self._service(r["service_id"])
                s.weekdays = sum(1 << i for i, c in enumerate(cols) if r.get(c) == "1")
                s.start = int(r["start_date"])
                s.end = int(r["end_date"])
        if "calendar_dates.txt" in names:
            for r in _rows(zf, "calendar_dates.txt"):
                s = self._service(r["service_id"])
                (s.added if r["exception_type"] == "1" else s.removed).add(int(r["date"]))

    def _service(self, service_id: str) -> Service:
        idx = self.service_index.get(service_id)
        if idx is None:
            idx = self.service_index[service_id] = len(self.services)
            self.services.append(Service())
        return self.services[idx]

    def _load_trips(self, zf: zipfile.ZipFile) -> None:
        self.trip_ids: list[str] = []
        self.trip_index: dict[str, int] = {}
        self.trip_headsign: list[str] = []
        route, service = array("i"), array("i")
        for r in _rows(zf, "trips.txt"):
            ri = self.route_index.get(r["route_id"])
            if ri is None:
                continue
            self._service(r["service_id"])
            self.trip_index[r["trip_id"]] = len(self.trip_ids)
            self.trip_ids.append(r["trip_id"])
            self.trip_headsign.append(r.get("trip_headsign", ""))
            route.append(ri)
            service.append(self.service_index[r["service_id"]])
        self.trip_route = np.frombuffer(route, dtype=np.int32).copy()
        self.trip_service = np.frombuffer(service, dtype=np.int32).copy()

    def _load_stop_times(self, zf: zipfile.ZipFile) -> None:
        trip, seq, arr_t, dep_t, pickup = (array("i") for _ in range(5))
        stop_names: list[str] = []
        stop_tmp: dict[str, int] = {}
        stop_col = array("i")
        for r in _rows(zf, "stop_times.txt"):
            ti = self.trip_index.get(r["trip_id"])
            if ti is None:
                continue
            a = r.get("arrival_time") or r.get("departure_time")
            d = r.get("departure_time") or r.get("arrival_time")
            if not a:
                continue
            sid = r["stop_id"]
            si = stop_tmp.get(sid)
            if si is None:
                si = stop_tmp[sid] = len(stop_names)
                stop_names.append(sid)
            trip.append(ti)
            seq.append(int(r["stop_sequence"]))
            stop_col.append(si)
            at, dt = _parse_time(a), _parse_time(d)
            arr_t.append(at)
            dep_t.append(max(at, dt))
            pickup.append(int(r.get("pickup_type") or 0))

        t = np.frombuffer(trip, dtype=np.int32)
        s = np.frombuffer(seq, dtype=np.int32)
        order = np.lexsort((s, t))  # podle spoje, uvnitř podle pořadí
        self.st_trip = t[order]
        self.st_stop_tmp = np.frombuffer(stop_col, dtype=np.int32)[order]
        self.st_arr = np.frombuffer(arr_t, dtype=np.int32)[order]
        self.st_dep = np.frombuffer(dep_t, dtype=np.int32)[order]
        self.st_pickup = np.frombuffer(pickup, dtype=np.int32)[order]
        self._stop_tmp_names = stop_names

        n_trips = len(self.trip_ids)
        counts = np.bincount(self.st_trip, minlength=n_trips)
        self.trip_offsets = np.zeros(n_trips + 1, dtype=np.int64)
        np.cumsum(counts, out=self.trip_offsets[1:])

    def _load_stops(self, zf: zipfile.ZipFile) -> None:
        used = set(self._stop_tmp_names)
        self.stop_ids: list[str] = []
        self.stop_names: list[str] = []
        self.stop_platform: list[str] = []
        self.stop_index: dict[str, int] = {}
        lat, lon, grp = array("d"), array("d"), array("i")

        self.group_ids: list[str] = []
        self.group_index: dict[str, int] = {}
        name_votes: list[dict[str, int]] = []
        sums: list[list[float]] = []

        for r in _rows(zf, "stops.txt"):
            sid = r["stop_id"]
            if sid not in used or (r.get("location_type") or "0") != "0":
                continue
            key = group_key(sid)
            g = self.group_index.get(key)
            if g is None:
                g = self.group_index[key] = len(self.group_ids)
                self.group_ids.append(key)
                name_votes.append({})
                sums.append([0.0, 0.0, 0])
            y, x = float(r["stop_lat"]), float(r["stop_lon"])
            name = r["stop_name"]
            name_votes[g][name] = name_votes[g].get(name, 0) + 1
            sums[g][0] += y
            sums[g][1] += x
            sums[g][2] += 1

            self.stop_index[sid] = len(self.stop_ids)
            self.stop_ids.append(sid)
            self.stop_names.append(name)
            m = _STOP_RE.match(sid)
            self.stop_platform.append(r.get("platform_code") or (m.group(2) if m else ""))
            lat.append(y)
            lon.append(x)
            grp.append(g)

        self.stop_lat = np.frombuffer(lat, dtype=np.float64).copy()
        self.stop_lon = np.frombuffer(lon, dtype=np.float64).copy()
        self.stop_group = np.frombuffer(grp, dtype=np.int32).copy()
        self.group_names = [max(v.items(), key=lambda kv: kv[1])[0] for v in name_votes]
        self.group_lat = np.array([s[0] / s[2] for s in sums], dtype=np.float64)
        self.group_lon = np.array([s[1] / s[2] for s in sums], dtype=np.float64)

        # Převod dočasných indexů zastávek ze stop_times na finální.
        remap = np.array([self.stop_index.get(n, -1) for n in self._stop_tmp_names], dtype=np.int32)
        self.st_stop = remap[self.st_stop_tmp] if len(self.st_stop_tmp) else self.st_stop_tmp
        del self.st_stop_tmp, self._stop_tmp_names

    def _build_departure_index(self) -> None:
        """Seřadí řádky, ze kterých se dá odjet, podle skupiny zastávek a času.

        Vynechává poslední zastávku spoje (tam se jen vystupuje) a zastávky
        s pickup_type=1, kde nástup není dovolen.
        """
        n = len(self.st_trip)
        is_last = np.zeros(n, dtype=bool)
        if n:
            is_last[self.trip_offsets[1:][np.diff(self.trip_offsets) > 0] - 1] = True
        ok = (~is_last) & (self.st_pickup != 1) & (self.st_stop >= 0)
        rows = np.nonzero(ok)[0]
        groups = self.stop_group[self.st_stop[rows]]
        order = np.lexsort((self.st_dep[rows], groups))
        self.dep_rows = rows[order]
        g_sorted = groups[order]
        self.group_offsets = np.searchsorted(g_sorted, np.arange(len(self.group_ids) + 1))
        # Nejdelší čas v řádu – kvůli spojům po půlnoci (časy přes 24:00).
        self.max_time = int(self.st_dep.max()) if n else 0

    # ---------- dotazy ----------

    def active_services(self, day: date) -> np.ndarray:
        # Cache na instanci, ne lru_cache na metodě: ten by držel staré
        # jízdní řády v paměti i po denním přenačtení.
        cache = self.__dict__.setdefault("_active_cache", {})
        hit = cache.get(day)
        if hit is None:
            if len(cache) > 8:
                cache.clear()
            hit = cache[day] = np.array([s.active(day) for s in self.services], dtype=bool)
        return hit

    def position_in_trip(self, row: int | np.ndarray) -> int | np.ndarray:
        return row - self.trip_offsets[self.st_trip[row]]

    def trip_rows(self, trip: int) -> tuple[int, int]:
        return int(self.trip_offsets[trip]), int(self.trip_offsets[trip + 1])

    def nearby_groups(self, lat: float, lon: float, limit: int) -> list[tuple[int, float]]:
        d = distance_m(lat, lon, self.group_lat, self.group_lon)
        idx = np.argsort(d)[:limit]
        return [(int(i), float(d[i])) for i in idx]

    def search_groups(self, query: str, limit: int) -> list[int]:
        q = _fold(query)
        if not q:
            return []
        starts, contains = [], []
        for i, name in enumerate(self.group_names):
            f = _fold(name)
            if f.startswith(q):
                starts.append(i)
            elif q in f:
                contains.append(i)
        return (sorted(starts, key=lambda i: self.group_names[i]) + contains)[:limit]


def _fold(text: str) -> str:
    import unicodedata

    norm = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in norm if not unicodedata.combining(c))


def distance_m(lat1, lon1, lat2, lon2):
    """Haversine v metrech; funguje se skaláry i numpy poli."""
    r = 6_371_000.0
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dp = p2 - p1
    dl = np.radians(np.asarray(lon2) - lon1)
    a = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * r * np.arcsin(np.sqrt(a))


def load_timetable(source: bytes | str, version: str | None = None) -> Timetable:
    data = io.BytesIO(source) if isinstance(source, bytes) else source
    with zipfile.ZipFile(data) as zf:
        return Timetable(zf, version=version)
