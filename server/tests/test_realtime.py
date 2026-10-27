from datetime import date

from conftest import local
from google.transit import gtfs_realtime_pb2 as rt

from dpmb_server.departures import departures_for_group
from dpmb_server.realtime import LiveTrip, Snapshot, parse_feed

DAY = date(2026, 10, 27)
# Mendlovo nám. a Česká (nástupiště 1); polovina cesty mezi nimi.
MENDLAK = (49.1910, 16.5930)
CESKA = (49.1960, 16.6060)
HALF = ((MENDLAK[0] + CESKA[0]) / 2, (MENDLAK[1] + CESKA[1]) / 2)


def feed(*vehicles: dict, ts: float) -> bytes:
    msg = rt.FeedMessage()
    msg.header.gtfs_realtime_version = "2.0"
    msg.header.timestamp = int(ts)
    for i, v in enumerate(vehicles):
        e = msg.entity.add()
        e.id = f"e{i}"
        vp = e.vehicle
        vp.trip.trip_id = v["trip"]
        vp.position.latitude, vp.position.longitude = v["pos"]
        vp.timestamp = int(v.get("ts", ts))
        vp.vehicle.label = v.get("label", f"v{i}")
        if "stop" in v:
            vp.stop_id = v["stop"]
        if v.get("stopped"):
            vp.current_status = rt.VehiclePosition.STOPPED_AT
        else:
            vp.current_status = rt.VehiclePosition.IN_TRANSIT_TO
    return msg.SerializeToString()


def test_delay_from_position_with_padded_stop_id(tt):
    # Podle řádu je v půli úseku v 08:02:30. Ve 08:04:30 tam je → 2 min zpoždění.
    now = local(2026, 10, 27, 8, 4, 30)
    snap = parse_feed(tt, feed({"trip": "T1", "pos": HALF, "stop": "U0100Z01"}, ts=now), now)
    live = snap.live(tt.trip_index["T1"], DAY)
    assert live is not None and live.delay == 120 and live.estimated
    assert live.position == 1 and not live.stopped

    deps = departures_for_group(tt, tt.group_index["U100"], now, snap)
    first = deps[0].to_json(now)
    assert first["line"] == "1"
    assert first["isLive"] and first["isEstimate"]
    assert first["delayMinutes"] == 2
    assert first["minutes"] == 3  # 08:06 + 2 min = 08:08, teď je 08:04:30


def test_delay_from_geometry_without_stop_id(tt):
    now = local(2026, 10, 27, 8, 4, 30)
    snap = parse_feed(tt, feed({"trip": "T1", "pos": HALF}, ts=now), now)
    live = snap.live(tt.trip_index["T1"], DAY)
    assert live is not None and abs(live.delay - 120) <= 5
    assert live.position == 1


def test_stopped_at_stop(tt):
    # Stojí na České 08:09, odjezd měl být 08:06 → 3 min.
    now = local(2026, 10, 27, 8, 9)
    snap = parse_feed(tt, feed({"trip": "T1", "pos": CESKA, "stop": "U100Z1", "stopped": True}, ts=now), now)
    live = snap.live(tt.trip_index["T1"], DAY)
    assert live.delay == 180 and live.stopped and live.position == 1
    deps = departures_for_group(tt, tt.group_index["U100"], now, snap)
    assert deps[0].line == "1" and deps[0].to_json(now)["minutes"] == 0


def test_early_vehicle_is_not_shown_earlier_than_schedule(tt):
    # Ve 08:00:30 je už v půli úseku (podle řádu 08:02:30) → náskok 2 min.
    now = local(2026, 10, 27, 8, 0, 30)
    snap = parse_feed(tt, feed({"trip": "T1", "pos": HALF, "stop": "U100Z1"}, ts=now), now)
    live = snap.live(tt.trip_index["T1"], DAY)
    assert live.delay == -120
    dep = departures_for_group(tt, tt.group_index["U100"], now, snap)[0]
    assert dep.predicted == dep.scheduled
    assert dep.delay_min == -2


def test_implausible_lead_is_dropped(tt):
    # „Náskok“ 20 minut = vozidlo má spoj jen předpřiřazený. Ignorovat.
    now = local(2026, 10, 27, 7, 42, 30)
    snap = parse_feed(tt, feed({"trip": "T1", "pos": HALF, "stop": "U100Z1"}, ts=now), now)
    assert snap.trips == {}


def test_stale_positions_are_dropped(tt):
    now = local(2026, 10, 27, 8, 30)
    snap = parse_feed(tt, feed({"trip": "T1", "pos": HALF, "ts": now - 20 * 60}, ts=now), now)
    assert snap.trips == {}


def test_unknown_trip_is_ignored(tt):
    now = local(2026, 10, 27, 8, 4)
    snap = parse_feed(tt, feed({"trip": "NEZNAMY", "pos": HALF}, ts=now), now)
    assert snap.trips == {}


def test_same_vehicle_on_two_calendar_variants_keeps_the_plausible_one(tt):
    # Stejné vozidlo hlášené na T1 (sedí) i na T2 (by vyšlo nesmyslně).
    now = local(2026, 10, 27, 8, 4, 30)
    payload = feed(
        {"trip": "T1", "pos": HALF, "stop": "U100Z1", "label": "1234"},
        {"trip": "T2", "pos": HALF, "stop": "U100Z2", "label": "1234"},
        ts=now,
    )
    snap = parse_feed(tt, payload, now)
    assert list(k[0] for k in snap.trips) == [tt.trip_index["T1"]]


def test_trip_that_passed_the_stop_is_hidden(tt):
    # Uměle velké zpoždění: vozidlo už jede ke Hlavnímu nádraží (pozice 2),
    # takže odjezd z České nesmí zůstat v seznamu, i když by čas vycházel.
    now = local(2026, 10, 27, 8, 10)
    snap = Snapshot(fetched_at=now, data_at=now,
                    trips={(tt.trip_index["T1"], DAY): LiveTrip(600, 2, False, int(now))})
    lines = [d.line for d in departures_for_group(tt, tt.group_index["U100"], now, snap)]
    assert "1" not in lines


def test_direct_trip_update_delay_wins(tt):
    now = local(2026, 10, 27, 8, 4)
    msg = rt.FeedMessage()
    msg.header.gtfs_realtime_version = "2.0"
    msg.header.timestamp = int(now)
    e = msg.entity.add()
    e.id = "tu"
    e.trip_update.trip.trip_id = "T1"
    e.trip_update.trip.start_date = "20261027"
    stu = e.trip_update.stop_time_update.add()
    stu.departure.delay = 240
    snap = parse_feed(tt, msg.SerializeToString(), now)
    live = snap.live(tt.trip_index["T1"], DAY)
    assert live.delay == 240 and not live.estimated
