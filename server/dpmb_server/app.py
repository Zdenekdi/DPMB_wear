"""HTTP API pro hodinky DPMB Wear.

Hodinky nedostanou celý jízdní řád – jen hotové odjezdy pro jednu zastávku
a seznam zastávek v okolí. Odpovědi jsou malé, aby šetřily rádio i baterii.
"""

from __future__ import annotations

import logging
import os
import secrets
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query

from .departures import departures_for_group
from .store import DEFAULT_GTFS_URL, DEFAULT_RT_URL, DataStore, Settings

ATTRIBUTION = "Zdroj dat: KORDIS JMK, a.s. (CC BY 4.0). Zpoždění je odhad z polohy vozidla."


def settings_from_env() -> Settings:
    rt = os.environ.get("RT_URL", DEFAULT_RT_URL)
    return Settings(
        gtfs_url=os.environ.get("GTFS_URL", DEFAULT_GTFS_URL),
        gtfs_file=os.environ.get("GTFS_FILE") or None,
        rt_url=rt or None,  # RT_URL="" vypne živá data
    )


def create_app(store: DataStore | None = None, api_key: str | None = None,
               preload: bool = True, background: bool = True) -> FastAPI:
    store = store or DataStore(settings_from_env())
    api_key = api_key if api_key is not None else os.environ.get("API_KEY", "")

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if preload:
            store.refresh_timetable(force=True)
        if background:
            store.start_background_refresh()
        yield

    app = FastAPI(title="DPMB Wear API", version="0.1.0", lifespan=lifespan)
    app.state.store = store

    def check_key(x_api_key: str | None = Header(default=None)) -> None:
        # Klíč je v APK, takže před odhodlaným útočníkem nechrání. Brání ale
        # tomu, aby si API náhodně našel a zatížil kdokoli z internetu.
        if api_key and not (x_api_key and secrets.compare_digest(x_api_key, api_key)):
            raise HTTPException(status_code=401, detail="Chybí nebo nesedí X-Api-Key")

    def stop_json(g: int, distance: float | None = None) -> dict:
        tt = store.timetable
        out = {
            "id": tt.group_ids[g],
            "name": tt.group_names[g],
            "lat": round(float(tt.group_lat[g]), 6),
            "lon": round(float(tt.group_lon[g]), 6),
        }
        if distance is not None:
            out["distanceM"] = int(round(distance))
        return out

    @app.get("/health")
    def health() -> dict:
        tt = store._tt  # noqa: SLF001 – health nesmí spouštět stahování
        snap = store._snapshot  # noqa: SLF001
        return {
            "ok": tt is not None,
            "gtfsVersion": tt.version if tt else None,
            "trips": len(tt.trip_ids) if tt else 0,
            "realtimeError": snap.error if snap else None,
        }

    @app.get("/v1/stops/nearby", dependencies=[Depends(check_key)])
    def nearby(lat: float = Query(ge=-90, le=90), lon: float = Query(ge=-180, le=180),
               limit: int = Query(5, ge=1, le=20)) -> dict:
        tt = store.timetable
        return {"stops": [stop_json(g, d) for g, d in tt.nearby_groups(lat, lon, limit)]}

    @app.get("/v1/stops/search", dependencies=[Depends(check_key)])
    def search(q: str = Query(min_length=1, max_length=60), limit: int = Query(10, ge=1, le=30)) -> dict:
        return {"stops": [stop_json(g) for g in store.timetable.search_groups(q, limit)]}

    @app.get("/v1/departures", dependencies=[Depends(check_key)])
    def departures(stop: str = Query(min_length=1, max_length=20),
                   limit: int = Query(15, ge=1, le=40),
                   window: int = Query(90, ge=10, le=240, description="minut dopředu")) -> dict:
        tt = store.timetable
        g = tt.group_index.get(stop)
        if g is None:
            raise HTTPException(status_code=404, detail=f"Zastávka {stop} neexistuje")
        now = time.time()
        snap = store.realtime(now)
        deps = departures_for_group(tt, g, now, snap, window_s=window * 60, limit=limit)
        rt_ok = bool(snap and snap.trips)
        return {
            "stop": stop_json(g),
            "generatedAt": int(now * 1000),
            "realtime": {
                "available": rt_ok,
                "ageSeconds": int(now - snap.data_at) if rt_ok else None,
            },
            "departures": [d.to_json(now) for d in deps],
            "attribution": ATTRIBUTION,
        }

    return app


def main() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    uvicorn.run(create_app(), host=os.environ.get("HOST", "0.0.0.0"),
                port=int(os.environ.get("PORT", "8080")))


if __name__ == "__main__":
    main()
