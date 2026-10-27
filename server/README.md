# DPMB Wear – server

Malá služba, která z otevřených dat KORDIS JMK počítá odjezdy pro hodinky.
Hodinky nestahují celý jízdní řád (desítky MB), dostanou jen hotový seznam
odjezdů pro jednu zastávku.

## Zdroje dat

| Data | Adresa | Obnova |
|---|---|---|
| Jízdní řád (GTFS) | `https://kordis-jmk.cz/gtfs/gtfs.zip` | kontrola každých 6 h podle `Last-Modified` |
| Polohy vozidel a výluky (GTFS-RT) | `https://kordis-jmk.cz/gtfs/gtfsReal.dat` | líně, nejvýš jednou za 15 s |

**Licence:** podle projektů, které data používají, CC BY 4.0. Před veřejným
spuštěním ověř přímo na webu KORDIS. Uvedení zdroje je v každé odpovědi
v poli `attribution` a aplikace ho musí zobrazit.

**Zpoždění je odhad.** Feed neobsahuje zpoždění, jen polohy. Server ho
dopočítává z polohy vozidla mezi dvěma zastávkami a jízdního řádu.
Proto platí:

- náskok se do času odjezdu nepromítá (radši o minutu dřív na zastávce než ujetý spoj),
- „náskok“ přes 8 min a zpoždění přes 3 h se zahazují jako chyba přiřazení,
- polohy starší 15 min se ignorují,
- spoj, který už zastávkou projel, se ze seznamu odebere,
- odpověď nese `isEstimate: true`.

Kdyby KORDIS do feedu přidal `TripUpdate` se zpožděním, použije se přímo
a `isEstimate` bude `false`.

## API

Všechny `/v1` endpointy vyžadují hlavičku `X-Api-Key`, pokud je nastavená
proměnná `API_KEY`. Klíč je v APK, takže před odhodlaným útočníkem nechrání,
jen brání náhodnému zatížení.

`GET /v1/stops/nearby?lat=49.196&lon=16.606&limit=5`

```json
{"stops": [{"id": "U100", "name": "Česká", "lat": 49.1961, "lon": 16.6062, "distanceM": 12}]}
```

`GET /v1/stops/search?q=ces` – hledání bez ohledu na diakritiku.

`GET /v1/departures?stop=U100&limit=15&window=90`

```json
{
  "stop": {"id": "U100", "name": "Česká", "lat": 49.1961, "lon": 16.6062},
  "generatedAt": 1793170000000,
  "realtime": {"available": true, "ageSeconds": 7},
  "departures": [
    {"line": "1", "destination": "Ečerova", "platform": "1",
     "scheduledTime": "08:06", "scheduledEpoch": 1793170000, "predictedEpoch": 1793170120,
     "minutes": 3, "delayMinutes": 2, "isLive": true, "isEstimate": true}
  ],
  "attribution": "Zdroj dat: KORDIS JMK, a.s. (CC BY 4.0). …"
}
```

ID zastávky je skupina (`U100`), ne konkrétní nástupiště (`U100Z1`).
Nástupiště je v poli `platform`.

`GET /health` – bez klíče, pro monitoring.

## Spuštění

```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -e '.[test]'
pytest                       # testy na syntetickém jízdním řádu
API_KEY=nejaky-klic dpmb-server   # http://localhost:8080
```

Proměnné prostředí:

| Proměnná | Výchozí | Význam |
|---|---|---|
| `API_KEY` | prázdné = bez klíče | klíč pro hodinky |
| `GTFS_URL` | KORDIS | odkud stahovat jízdní řád |
| `GTFS_FILE` | – | místní soubor místo stahování (vývoj) |
| `RT_URL` | KORDIS | `RT_URL=` vypne živá data |
| `PORT` | 8080 | |

Docker:

```bash
docker build -t dpmb-server server
docker run -p 8080:8080 -e API_KEY=nejaky-klic dpmb-server
```

## Nároky

Jízdní řád velikosti celého kraje (~2,5 mil. řádků `stop_times`) se načte
zhruba za 8 s a v paměti zabere několik set MB. Počítej s instancí s 1 GB RAM.
Dotaz na odjezdy trvá pod 1 ms.

Server drží data v paměti, takže běží jako jedna instance. Pro hodinky jednoho
člověka nebo pár stovek uživatelů to stačí s rezervou.
