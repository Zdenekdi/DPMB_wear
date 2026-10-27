"""Stahování a obnova dat. Jízdní řád jednou za čas, živá data líně na požádání."""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import httpx

from .gtfs import Timetable, load_timetable
from .realtime import Snapshot, parse_feed

log = logging.getLogger("dpmb.store")

DEFAULT_GTFS_URL = "https://kordis-jmk.cz/gtfs/gtfs.zip"
DEFAULT_RT_URL = "https://kordis-jmk.cz/gtfs/gtfsReal.dat"


@dataclass
class Settings:
    gtfs_url: str = DEFAULT_GTFS_URL
    gtfs_file: str | None = None
    rt_url: str | None = DEFAULT_RT_URL
    rt_cache_s: float = 15.0
    gtfs_check_s: float = 6 * 3600
    timeout_s: float = 20.0
    user_agent: str = "DPMBWear-server/0.1 (+https://nexusstack.eu)"


class DataStore:
    def __init__(self, settings: Settings, client: httpx.Client | None = None):
        self.settings = settings
        self.client = client or httpx.Client(
            timeout=settings.timeout_s,
            headers={"User-Agent": settings.user_agent},
            follow_redirects=True,
        )
        self._tt: Timetable | None = None
        self._tt_checked = 0.0
        self._snapshot: Snapshot | None = None
        self._tt_lock = threading.Lock()
        self._rt_lock = threading.Lock()

    # ---------- jízdní řád ----------

    @property
    def timetable(self) -> Timetable:
        if self._tt is None:
            self.refresh_timetable(force=True)
        assert self._tt is not None
        return self._tt

    def refresh_timetable(self, force: bool = False) -> bool:
        """Stáhne jízdní řád, pokud se změnil. Vrací True, když se načetl nový."""
        with self._tt_lock:
            if not force and time.time() - self._tt_checked < self.settings.gtfs_check_s:
                return False
            self._tt_checked = time.time()
            if self.settings.gtfs_file:
                if self._tt is not None and not force:
                    return False
                data = Path(self.settings.gtfs_file).read_bytes()
                version = f"file:{Path(self.settings.gtfs_file).stat().st_mtime:.0f}"
            else:
                version = None
                if self._tt is not None:
                    head = self.client.head(self.settings.gtfs_url)
                    version = head.headers.get("last-modified") or head.headers.get("etag")
                    if version and version == self._tt.version:
                        return False
                resp = self.client.get(self.settings.gtfs_url)
                resp.raise_for_status()
                data = resp.content
                version = resp.headers.get("last-modified") or resp.headers.get("etag") or version
            started = time.time()
            tt = load_timetable(data, version=version)
            log.info("GTFS načteno za %.1f s: %d spojů, %d skupin zastávek, verze %s",
                     time.time() - started, len(tt.trip_ids), len(tt.group_ids), version)
            self._tt = tt
            self._snapshot = None  # indexy spojů se změnily
            return True

    def start_background_refresh(self) -> threading.Thread:
        def loop() -> None:
            while True:
                time.sleep(self.settings.gtfs_check_s)
                try:
                    self.refresh_timetable()
                except Exception:  # noqa: BLE001 – server musí běžet dál se starým řádem
                    log.exception("Obnova GTFS selhala, zůstává předchozí verze")

        t = threading.Thread(target=loop, name="gtfs-refresh", daemon=True)
        t.start()
        return t

    # ---------- živá data ----------

    def realtime(self, now: float | None = None) -> Snapshot | None:
        if not self.settings.rt_url:
            return None
        now = now or time.time()
        snap = self._snapshot
        if snap and now - snap.fetched_at < self.settings.rt_cache_s:
            return snap
        with self._rt_lock:
            snap = self._snapshot
            if snap and now - snap.fetched_at < self.settings.rt_cache_s:
                return snap
            tt = self.timetable
            try:
                resp = self.client.get(self.settings.rt_url, timeout=8.0)
                resp.raise_for_status()
                snap = parse_feed(tt, resp.content, now)
            except Exception as e:  # noqa: BLE001
                log.warning("GTFS-RT nedostupné: %s", e)
                # Krátký výpadek feedu: držíme poslední polohy, ale jen pár minut.
                old = self._snapshot
                if old and old.trips and now - old.data_at < 120:
                    snap = Snapshot(fetched_at=now, feed_timestamp=old.feed_timestamp,
                                    data_at=old.data_at, trips=old.trips, error=str(e))
                else:
                    snap = Snapshot(fetched_at=now, error=str(e))
            self._snapshot = snap
            return snap
