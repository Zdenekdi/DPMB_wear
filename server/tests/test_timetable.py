from datetime import date

from conftest import local

from dpmb_server.departures import departures_for_group
from dpmb_server.gtfs import group_key, normalize_stop_id
from dpmb_server.timeutil import service_day_start


def test_stop_ids():
    assert normalize_stop_id("U01472Z02") == "U1472Z2"
    assert normalize_stop_id("U1472Z2") == "U1472Z2"
    assert normalize_stop_id("XYZ") == "XYZ"
    assert group_key("U1472Z2") == "U1472"


def test_platforms_merge_into_one_group(tt):
    g = tt.group_index["U100"]
    assert tt.group_names[g] == "Česká"
    assert "U999" not in tt.group_index, "zastávky bez spojů se nenačítají"


def test_no_departures_from_terminus(tt):
    g = tt.group_index["U400"]
    assert tt.group_offsets[g + 1] - tt.group_offsets[g] == 0


def test_scheduled_departures(tt):
    now = local(2026, 10, 27, 8, 2)
    deps = departures_for_group(tt, tt.group_index["U100"], now, None)
    assert [(d.line, d.destination, d.platform) for d in deps] == [
        ("1", "Konečná", "1"),
        ("6", "Hlavní nádraží", "2"),
    ], "T4 má 27. 10. zrušenou službu"
    assert [d.to_json(now)["minutes"] for d in deps] == [4, 18]
    assert all(not d.is_live and d.delay_min is None for d in deps)
    assert deps[0].to_json(now)["scheduledTime"] == "08:06"


def test_service_added_by_calendar_dates(tt):
    now = local(2026, 10, 28, 8, 15)
    lines = [d.destination for d in departures_for_group(tt, tt.group_index["U100"], now, None)]
    assert "Zrušený" in lines


def test_after_midnight_belongs_to_previous_service_day(tt):
    now = local(2026, 10, 28, 0, 5)
    deps = departures_for_group(tt, tt.group_index["U100"], now, None)
    assert [(d.line, d.to_json(now)["minutes"]) for d in deps] == [("N1", 5)]
    # Prázdný headsign → název konečné zastávky.
    assert deps[0].destination == "Hlavní nádraží"


def test_departed_more_than_grace_is_hidden(tt):
    now = local(2026, 10, 27, 8, 7)
    lines = [d.line for d in departures_for_group(tt, tt.group_index["U100"], now, None)]
    assert "1" not in lines


def test_daytime_is_local_time_on_dst_change_days():
    # 29. 3. a 25. 10. 2026 se mění čas. Denní spoj 08:00 musí vyjít jako
    # místních 08:00, ne o hodinu vedle.
    for d in (date(2026, 3, 29), date(2026, 10, 25)):
        assert service_day_start(d) + 8 * 3600 == local(d.year, d.month, d.day, 8, 0)


def test_noon_is_noon_on_dst_day():
    # GTFS definuje den provozu jako „poledne minus 12 h“, takže poledne sedí vždy.
    for d in (date(2026, 3, 29), date(2026, 10, 25), date(2026, 10, 27)):
        assert service_day_start(d) + 12 * 3600 == local(d.year, d.month, d.day, 12, 0)


def test_search_ignores_diacritics(tt):
    names = [tt.group_names[g] for g in tt.search_groups("ces", 5)]
    assert names == ["Česká"]
    assert [tt.group_names[g] for g in tt.search_groups("nadrazi", 5)] == ["Hlavní nádraží"]


def test_nearby(tt):
    (g, dist), *_ = tt.nearby_groups(49.1961, 16.6061, 3)
    assert tt.group_ids[g] == "U100"
    assert dist < 50
