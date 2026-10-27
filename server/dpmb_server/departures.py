"""Odjezdy ze skupiny zastávek: jízdní řád + živý odhad zpoždění."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .gtfs import Timetable
from .realtime import Snapshot
from .timeutil import hhmm, neighbours, prague_date, service_day_start

# Spoje, které podle řádu odjely před chvílí, mohou být zpožděné a stále
# před námi. Proto se kandidáti berou i z minulosti.
LOOKBEHIND_S = 30 * 60
# Odjezd, který „odjel“ před méně než 30 s, ještě ukážeme – hodinky obnovují
# po 30 s a čísla se přepočítávají lokálně.
GRACE_S = 30


@dataclass(frozen=True)
class Departure:
    line: str
    destination: str
    platform: str
    scheduled: int
    """Odjezd podle jízdního řádu, epoch sekundy."""
    predicted: int
    """Očekávaný odjezd, epoch sekundy."""
    delay_min: int | None
    is_live: bool
    estimated: bool

    def to_json(self, now: float) -> dict:
        return {
            "line": self.line,
            "destination": self.destination,
            "platform": self.platform,
            "scheduledTime": hhmm(self.scheduled),
            "scheduledEpoch": self.scheduled,
            "predictedEpoch": self.predicted,
            "minutes": max(0, int((self.predicted - now) // 60)),
            "delayMinutes": self.delay_min,
            "isLive": self.is_live,
            "isEstimate": self.estimated,
        }


def departures_for_group(tt: Timetable, group: int, now: float, snapshot: Snapshot | None,
                         window_s: int = 90 * 60, limit: int = 20) -> list[Departure]:
    g0, g1 = int(tt.group_offsets[group]), int(tt.group_offsets[group + 1])
    rows = tt.dep_rows[g0:g1]
    if len(rows) == 0:
        return []
    trips = tt.st_trip[rows]
    dep = tt.st_dep[rows].astype(np.int64)
    positions = tt.position_in_trip(rows)

    out: list[Departure] = []
    today = prague_date(now)
    for day in neighbours(today):
        base = service_day_start(day)
        sched = base + dep
        active = tt.active_services(day)[tt.trip_service[trips]]
        mask = active & (sched >= now - LOOKBEHIND_S) & (sched <= now + window_s)
        for i in np.nonzero(mask)[0]:
            trip = int(trips[i])
            s = int(sched[i])
            live = snapshot.live(trip, day) if snapshot else None
            if live is not None and live.has_passed(int(positions[i])):
                continue
            if live is not None:
                # Náskok nepromítáme: vozidlo na zastávce obvykle počká a hlášený
                # náskok bývá spíš chyba přiřazení než skutečnost. Lepší je, když
                # člověk přijde o minutu dřív, než aby mu ujel spoj.
                predicted = s + max(0, live.delay)
                delay_min = int(round(live.delay / 60))
            else:
                predicted = s
                delay_min = None
            if predicted < now - GRACE_S or predicted > now + window_s:
                continue
            stop = int(tt.st_stop[rows[i]])
            destination = tt.trip_headsign[trip]
            if not destination:
                last = tt.trip_rows(trip)[1] - 1
                destination = tt.stop_names[int(tt.st_stop[last])]
            out.append(Departure(
                line=tt.route_short[int(tt.trip_route[trip])],
                destination=destination,
                platform=tt.stop_platform[stop],
                scheduled=s,
                predicted=predicted,
                delay_min=delay_min,
                is_live=live is not None,
                estimated=live.estimated if live else False,
            ))
    out.sort(key=lambda d: (d.predicted, d.line))
    return out[:limit]
