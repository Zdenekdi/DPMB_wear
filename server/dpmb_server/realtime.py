"""Zpracování GTFS-RT feedu KORDIS JMK.

Feed obsahuje polohy vozidel a výluky, ale ZPOŽDĚNÍ NEOBSAHUJE. Odhadujeme
ho z polohy vozidla mezi zastávkami a jízdního řádu. Je to odhad: vozidlo
stojící na semaforu za zastávkou vypadá stejně jako vozidlo, které na
zastávce ještě nebylo. Proto odhad držíme v rozumných mezích a výsledek
označujeme jako odhad.

Kdyby KORDIS do feedu někdy přidal TripUpdate se zpožděním, použije se
přímo a odhad z polohy se pro daný spoj přeskočí.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date

import numpy as np
from google.transit import gtfs_realtime_pb2 as rt

from .gtfs import Timetable, normalize_stop_id
from .timeutil import neighbours, parse_gtfs_date, prague_date, service_day_start

MAX_POSITION_AGE_S = 15 * 60
# Výrazný „náskok“ obvykle znamená, že vozidlo má spoj jen předpřiřazený
# a ještě dojíždí ten předchozí. Takový odhad je horší než žádný.
MIN_DELAY_S = -8 * 60
MAX_DELAY_S = 3 * 3600
# Jak daleko od zastávky ještě považujeme vozidlo za stojící v ní.
AT_STOP_RADIUS_M = 35.0

_STOPPED_AT = rt.VehiclePosition.STOPPED_AT


@dataclass(frozen=True)
class LiveTrip:
    delay: int
    """Zpoždění v sekundách, záporné = náskok."""
    position: int
    """Index zastávky ve spoji, na které vozidlo stojí nebo ke které jede. -1 = nevíme."""
    stopped: bool
    timestamp: int
    estimated: bool = True
    """True = dopočteno z polohy, False = zpoždění přišlo přímo ve feedu."""

    def has_passed(self, position: int) -> bool:
        """Spoj už zastávkou na pozici `position` projel."""
        if self.position < 0:
            return False
        return position < self.position


@dataclass
class Snapshot:
    fetched_at: float
    """Kdy jsme se naposled ptali (kvůli cache)."""
    feed_timestamp: int = 0
    data_at: float = 0.0
    """Kdy platí polohy v [trips]. Při výpadku feedu zůstává starší než fetched_at."""
    trips: dict[tuple[int, date], LiveTrip] = field(default_factory=dict)
    error: str | None = None

    def live(self, trip: int, day: date) -> LiveTrip | None:
        return self.trips.get((trip, day))


# ---------- geometrie ----------


def _xy(lat: float, lon: float, lat0: float, lon0: float) -> tuple[float, float]:
    """Lokální rovinné souřadnice v metrech. Na vzdálenost dvou zastávek stačí."""
    return (lon - lon0) * math.cos(math.radians(lat0)) * 111_320.0, (lat - lat0) * 110_540.0


def project(lat: float, lon: float, a: tuple[float, float], b: tuple[float, float]) -> tuple[float, float]:
    """Promítne bod na úsečku a→b. Vrací (podíl 0..1, vzdálenost od úsečky v m)."""
    bx, by = _xy(b[0], b[1], a[0], a[1])
    px, py = _xy(lat, lon, a[0], a[1])
    seg2 = bx * bx + by * by
    f = 0.0 if seg2 == 0 else max(0.0, min(1.0, (px * bx + py * by) / seg2))
    dx, dy = px - f * bx, py - f * by
    return f, math.hypot(dx, dy)


# ---------- určení polohy ve spoji ----------


@dataclass(frozen=True)
class _Fix:
    day: date
    position: int
    stopped: bool
    scheduled: float
    """Kdy podle jízdního řádu měl být spoj v tomhle místě."""


def _fix_by_stop(tt: Timetable, trip: int, day: date, stop: int, ts: int,
                 stopped: bool, lat: float, lon: float) -> _Fix | None:
    r0, r1 = tt.trip_rows(trip)
    base = service_day_start(day)
    best: _Fix | None = None
    for p in np.nonzero(tt.st_stop[r0:r1] == stop)[0]:
        p = int(p)
        arr = base + int(tt.st_arr[r0 + p])
        dep = base + int(tt.st_dep[r0 + p])
        if stopped or p == 0:
            sched = float(min(max(ts, arr), dep))
        else:
            prev = r0 + p - 1
            a = (tt.stop_lat[tt.st_stop[prev]], tt.stop_lon[tt.st_stop[prev]])
            b = (tt.stop_lat[stop], tt.stop_lon[stop])
            f, _ = project(lat, lon, a, b)
            prev_dep = base + int(tt.st_dep[prev])
            sched = prev_dep + f * (arr - prev_dep)
        fix = _Fix(day, p, stopped, sched)
        if best is None or abs(ts - fix.scheduled) < abs(ts - best.scheduled):
            best = fix
    return best


def _fix_by_geometry(tt: Timetable, trip: int, day: date, ts: int,
                     lat: float, lon: float) -> _Fix | None:
    """Bez ID zastávky: najde úsek trasy, ke kterému má vozidlo nejblíž."""
    r0, r1 = tt.trip_rows(trip)
    if r1 - r0 < 2:
        return None
    base = service_day_start(day)
    stops = tt.st_stop[r0:r1]
    best: tuple[float, float, _Fix] | None = None
    for p in range(1, r1 - r0):
        a = (tt.stop_lat[stops[p - 1]], tt.stop_lon[stops[p - 1]])
        b = (tt.stop_lat[stops[p]], tt.stop_lon[stops[p]])
        f, dist = project(lat, lon, a, b)
        prev_dep = base + int(tt.st_dep[r0 + p - 1])
        arr = base + int(tt.st_arr[r0 + p])
        sched = prev_dep + f * (arr - prev_dep)
        seg_len = math.hypot(*_xy(b[0], b[1], a[0], a[1]))
        if f * seg_len <= AT_STOP_RADIUS_M and dist <= AT_STOP_RADIUS_M:
            fix = _Fix(day, p - 1, True, float(min(max(ts, base + int(tt.st_arr[r0 + p - 1])), prev_dep)))
        elif (1 - f) * seg_len <= AT_STOP_RADIUS_M and dist <= AT_STOP_RADIUS_M:
            fix = _Fix(day, p, True, float(min(max(ts, arr), base + int(tt.st_dep[r0 + p]))))
        else:
            fix = _Fix(day, p, False, sched)
        key = (round(dist / 50.0), abs(ts - fix.scheduled))  # vzdálenost s tolerancí, pak čas
        if best is None or key < best[:2]:
            best = (key[0], key[1], fix)
    return best[2] if best else None


def locate(tt: Timetable, trip: int, ts: int, lat: float, lon: float,
           stop: int, stopped: bool, start_date: date | None) -> _Fix | None:
    days = [start_date] if start_date else neighbours(prague_date(ts))
    best: _Fix | None = None
    for day in days:
        if not tt.active_services(day)[tt.trip_service[trip]]:
            continue
        if stop >= 0:
            fix = _fix_by_stop(tt, trip, day, stop, ts, stopped, lat, lon)
        else:
            fix = _fix_by_geometry(tt, trip, day, ts, lat, lon)
        if fix and (best is None or abs(ts - fix.scheduled) < abs(ts - best.scheduled)):
            best = fix
    if best is None or abs(ts - best.scheduled) > 6 * 3600:
        return None
    return best


def estimate_delay(tt: Timetable, trip: int, fix: _Fix, ts: int) -> int | None:
    r0, r1 = tt.trip_rows(trip)
    base = service_day_start(fix.day)
    p = fix.position
    arr = base + int(tt.st_arr[r0 + p])
    dep = base + int(tt.st_dep[r0 + p])
    if fix.stopped:
        # Stojí na konečné dlouho po příjezdu: spoj skončil, zpoždění nevíme.
        if p == r1 - r0 - 1 and ts > arr + 120:
            return None
        if ts < arr:
            return ts - arr
        if ts <= dep:
            return 0
        return ts - dep
    if p == 0:
        # Jede na výchozí zastávku: zpožděný je, až když měl už odjet.
        return max(0, ts - dep)
    return int(round(ts - fix.scheduled))


# ---------- feed ----------


def parse_feed(tt: Timetable, payload: bytes, now: float) -> Snapshot:
    feed = rt.FeedMessage()
    feed.ParseFromString(payload)
    snap = Snapshot(fetched_at=now, feed_timestamp=int(feed.header.timestamp), data_at=now)

    direct: dict[tuple[int, date], LiveTrip] = {}
    # Feed posílá totéž vozidlo víckrát, s ID spojů pro různé varianty
    # kalendáře (školní / prázdninový). Necháme kandidáta, který časově sedí.
    candidates: dict[str, tuple[int, int, date, LiveTrip]] = {}

    for entity in feed.entity:
        if entity.HasField("trip_update"):
            tu = entity.trip_update
            trip = tt.trip_index.get(tu.trip.trip_id, -1)
            if trip < 0:
                continue
            delay = None
            for stu in tu.stop_time_update:
                ev = stu.departure if stu.HasField("departure") else stu.arrival
                if ev.HasField("delay"):
                    delay = ev.delay
                    break
            if delay is None and tu.HasField("delay"):
                delay = tu.delay
            if delay is None:
                continue
            ts = int(tu.timestamp or feed.header.timestamp or now)
            day = parse_gtfs_date(tu.trip.start_date) if tu.trip.start_date else prague_date(ts)
            direct[(trip, day)] = LiveTrip(delay, -1, False, ts, estimated=False)

        if not entity.HasField("vehicle"):
            continue
        vp = entity.vehicle
        if not vp.HasField("position"):
            continue
        ts = int(vp.timestamp or feed.header.timestamp or now)
        if now - ts > MAX_POSITION_AGE_S:
            continue
        trip = tt.trip_index.get(vp.trip.trip_id, -1)
        if trip < 0:
            continue
        stop = tt.stop_index.get(normalize_stop_id(vp.stop_id), -1) if vp.stop_id else -1
        stopped = vp.current_status == _STOPPED_AT and stop >= 0
        start_date = parse_gtfs_date(vp.trip.start_date) if vp.trip.start_date else None
        lat, lon = vp.position.latitude, vp.position.longitude

        fix = locate(tt, trip, ts, lat, lon, stop, stopped, start_date)
        if fix is None:
            continue
        delay = estimate_delay(tt, trip, fix, ts)
        if delay is None or not (MIN_DELAY_S <= delay <= MAX_DELAY_S):
            continue

        live = LiveTrip(delay, fix.position, fix.stopped, ts)
        vehicle = vp.vehicle.label or vp.vehicle.id or entity.id
        # Rozhoduje, jak dobře poloha sedí na řád, ne výsledné zpoždění: vozidlo
        # jedoucí na výchozí zastávku dalšího spoje má zpoždění 0 z definice.
        fit = int(abs(ts - fix.scheduled))
        prev = candidates.get(vehicle)
        if prev is None or fit < prev[0]:
            candidates[vehicle] = (fit, trip, fix.day, live)

    for _, trip, day, live in candidates.values():
        # Spřažené tramvaje = dvě vozidla na jednom spoji. Stačí jedno.
        snap.trips.setdefault((trip, day), live)
    for key, live in direct.items():
        old = snap.trips.get(key)
        snap.trips[key] = LiveTrip(live.delay, old.position if old else -1,
                                   old.stopped if old else False, live.timestamp, estimated=False)
    return snap
