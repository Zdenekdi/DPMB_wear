"""Časové pomůcky. Vše v pásmu Europe/Prague bez ohledu na pásmo serveru."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta
from functools import lru_cache
from zoneinfo import ZoneInfo

TZ = ZoneInfo("Europe/Prague")


def prague_date(epoch: float) -> date:
    """Kalendářní den v Praze pro daný okamžik."""
    return datetime.fromtimestamp(epoch, TZ).date()


@lru_cache(maxsize=64)
def service_day_start(day: date) -> int:
    """Referenční čas dne provozu podle GTFS: místní poledne minus 12 hodin.

    Časy v stop_times se počítají od tohoto okamžiku, ne od půlnoci. Ve dnech
    změny času to dělá rozdíl jedné hodiny, a právě proto to GTFS takhle definuje.
    """
    noon = datetime.combine(day, time(12, 0), TZ)
    return int(noon.timestamp()) - 12 * 3600


def gtfs_date(day: date) -> int:
    """Datum jako číslo YYYYMMDD, tak jak ho používá calendar.txt."""
    return day.year * 10000 + day.month * 100 + day.day


def parse_gtfs_date(value: int | str) -> date:
    v = int(value)
    return date(v // 10000, (v // 100) % 100, v % 100)


def hhmm(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, TZ).strftime("%H:%M")


def neighbours(day: date) -> list[date]:
    """Včera, dnes, zítra. Spoje po půlnoci patří do předchozího dne provozu."""
    return [day - timedelta(days=1), day, day + timedelta(days=1)]
