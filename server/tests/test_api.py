import pytest
from conftest import local
from fastapi.testclient import TestClient

import dpmb_server.app as app_module
from dpmb_server.store import DataStore, Settings


@pytest.fixture()
def client(tmp_path, gtfs_bytes, monkeypatch):
    f = tmp_path / "gtfs.zip"
    f.write_bytes(gtfs_bytes)
    store = DataStore(Settings(gtfs_file=str(f), rt_url=None))
    monkeypatch.setattr(app_module.time, "time", lambda: local(2026, 10, 27, 8, 2))
    app = app_module.create_app(store=store, api_key="tajne", background=False)
    with TestClient(app) as c:
        c.headers["X-Api-Key"] = "tajne"
        yield c


def test_requires_key(client):
    r = client.get("/v1/stops/nearby", params={"lat": 49.196, "lon": 16.606},
                   headers={"X-Api-Key": "spatne"})
    assert r.status_code == 401


def test_health_is_public(client):
    r = client.get("/health", headers={"X-Api-Key": ""})
    assert r.status_code == 200 and r.json()["ok"]


def test_nearby(client):
    r = client.get("/v1/stops/nearby", params={"lat": 49.1961, "lon": 16.6061, "limit": 2})
    stops = r.json()["stops"]
    assert stops[0]["id"] == "U100" and stops[0]["name"] == "Česká"
    assert stops[0]["distanceM"] < 50
    assert len(stops) == 2


def test_search(client):
    assert client.get("/v1/stops/search", params={"q": "mendl"}).json()["stops"][0]["id"] == "U300"


def test_departures(client):
    body = client.get("/v1/departures", params={"stop": "U100"}).json()
    assert body["stop"]["name"] == "Česká"
    assert body["realtime"] == {"available": False, "ageSeconds": None}
    assert [d["line"] for d in body["departures"]] == ["1", "6"]
    assert body["departures"][0]["minutes"] == 4
    assert "KORDIS" in body["attribution"]


def test_unknown_stop(client):
    assert client.get("/v1/departures", params={"stop": "U12345"}).status_code == 404
