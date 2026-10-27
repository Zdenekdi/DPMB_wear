# DPMB Wear

Aplikace pro Wear OS: odjezdy MHD v Brně, SMS jízdenky, QR kód z Brno iD.

---

## Stav dat — přečti první

Aplikace umí tři zdroje odjezdů. Vybírá je `DeparturesRepository.defaultSource()`
podle `local.properties` (soubor je v `.gitignore`):

| Pořadí | Zdroj | Nastavení | Stav |
|---|---|---|---|
| 1 | **Vlastní server** nad otevřenými daty KORDIS (`server/`) | `DPMB_SERVER_URL`, `DPMB_SERVER_KEY` | hlavní cesta |
| 2 | API `mapa.idsjmk.cz` s klíčem | `IDS_API_KEY` | jen pro smluvní partnery KORDIS |
| 3 | Ukázková data | nic | aplikace to na obrazovce přizná |

### Proč vlastní server

KORDIS JMK v roce 2026 pustil API `mapa.idsjmk.cz` jen smluvním partnerům.
Zároveň ale veřejně vydává jízdní řád (GTFS) a polohy vozidel (GTFS-RT)
na `kordis-jmk.cz/gtfs/`, podle projektů, které je používají, pod CC BY 4.0.
Server z nich počítá odjezdy; hodinky dostanou jen hotový seznam pro jednu
zastávku. Podrobnosti a spuštění v [`server/README.md`](server/README.md).

Dvě věci, které z toho plynou:

- **Zpoždění je odhad.** Feed obsahuje polohy, ne zpoždění. Server ho počítá
  z polohy vozidla mezi zastávkami. Aplikace u spoje ukáže `LIVE +3`
  a pod odjezdy upozornění „Zpoždění je odhad“.
- **Uvedení zdroje je povinné** (CC BY 4.0). Text je pod seznamem odjezdů.

### Vyzkoušení se serverem na Macu

```bash
cd server && pip install -e . && API_KEY=lokalni dpmb-server
```

Do `local.properties`:

```
DPMB_SERVER_URL=http://192.168.x.x:8080/
DPMB_SERVER_KEY=lokalni
```

Ladicí build dovoluje `http://` (viz `app/src/debug/AndroidManifest.xml`),
release jen `https://`.

### Klíč a veřejná distribuce

`DPMB_SERVER_KEY` je v APK, takže z něj jde dekompilací získat. Brání jen
tomu, aby server náhodně zatížil kdokoli z internetu.

---

## Architektura

```
data/
  DeparturesSource        rozhraní — jediné místo, kde se rozhoduje o zdroji dat
  ServerDeparturesSource    vlastní server nad otevřenými daty KORDIS
  DpmbServerApi             Retrofit rozhraní serveru + ServerClient
  FakeDeparturesSource      ukázková data, když není server ani klíč
  IdsJmkDeparturesSource    API KORDIS JMK s klíčem (smluvní partneři)
  IdsJmkApi                 Retrofit + OkHttp s timeouty
  DeparturesRepository      cache a výběr zdroje
  StopsRepository           nejbližší zastávky (ze serveru, jinak statické),
                            paměť zastávek pro dlaždici a komplikaci
  model/Models.kt           Stop, Departure, DeparturesResult

presentation/
  MainActivity              pager se třemi stránkami, ambient stav
  DeparturesScreen          odjezdy
  SmsScreen                 SMS jízdenky
  QrScreen                  QR kód
  theme/Color.kt            barevné tokeny + barvy linek IDS JMK
  theme/Theme.kt            Wear M3 ColorScheme

tile/                       dlaždice se třemi nejbližšími odjezdy
complication/               komplikace na ciferník s nejbližším odjezdem
```

### Zásady, které se vyplatí držet

**Datová vrstva nezná Compose.** `Departure` nenese barvu — tu si odvodí
každý konzument z čísla linky přes `LineColors.forLine()`. Díky tomu používá
stejný model obrazovka, dlaždice i komplikace.

**Barvy se nikdy nepíšou natvrdo.** Všechny jsou v `theme/Color.kt` a dělí se
na dvě vrstvy, které se nemíchají:

- *chrome* — ovládací prvky aplikace, odvozené od firemní červené DPMB,
- *data* (`LineColors`) — barvy linek podle IDS JMK, výhradně jako pilulka
  s číslem linky.

Modrá `#35659C` byla z ovládacích prvků odstraněna právě proto, že kolidovala
s barvou autobusových linek.

**Typografie jde z `MaterialTheme.typography`,** ne z natvrdo zadaných `sp`.
Jinak aplikace ignoruje uživatelovo nastavení velikosti písma.

**Nikdy netvrdit, že data jsou živá, když nejsou.** `DeparturesUiState.Success`
nese `isDemo` a `isStale` a UI to musí přiznat. Uživatel, kterému aplikace ukáže
neexistující odjezd, zůstane stát na zastávce.

**Na hodinkách je baterie prvořadá.** Poloha se bere z `lastLocation`, plný GPS
fix jen jako záloha. Automatické obnovování běží pouze dokud je obrazovka vpředu.
V ambientu nesmí svítit sytá plocha.

---

## Testy

```bash
./gradlew :app:testDebugUnitTest
```

Pokrývají logiku, která selhává tiše — špatný výsledek vypadá jako důvěryhodné číslo:

- `ParseTimeMarkTest` — převod značky času z API, přetok přes půlnoc oběma směry
- `CacheAdjustmentTest` — stárnutí cache a zahazování odjetých spojů
- `LineColorsTest` — přiřazení barvy linky, včetně regrese na pořadí podmínek
- `ServerMappingTest` — převod odpovědi serveru, zahazování neúplných řádků

Server má vlastní testy: `cd server && pytest`.

Obě testované funkce berou čas jako parametr, aby šel v testu zafixovat.

---

## Co zbývá

**Před ostrým provozem**

- Ověřit licenci dat přímo u KORDIS a podmínky užití GTFS-RT.
- Nasadit server (Docker, 1 GB RAM) s https a vyplnit `DPMB_SERVER_URL`.
- Porovnat odhad zpoždění se skutečností na pár spojích.
- Ze serveru přichází i ID zastávek ve tvaru `U1472`; oblíbené zastávky
  uložené se starými ID (`1017`) se se serverem nenajdou.

**Vyžaduje zařízení**

Celý projekt zatím prošel jen statickou kontrolou. Ověřit je potřeba hlavně:
umístění indikátoru stránek na kulatém displeji, vzhled ambientního režimu
a skutečnou skenovatelnost QR kódu čtečkou.

**Úklid**

- `material-icons-extended` je velká závislost kvůli osmi ikonám.
- `wear-compose-navigation` a `wear-input` se nepoužívají, druhá je navíc alpha.
- Compose BOM `2024.09.00` je starší, než co si žádá Wear Compose 1.5.6.
- Verze Kotlinu v katalogu (2.2.10) nemusí sedět s tím, co vyžaduje AGP 9.2.1.
  Kdyby build spadl na neshodě Compose compiler pluginu, je to tohle.

---

## Sestavení

```bash
./gradlew :app:assembleDebug
```

`minSdk 30`, `targetSdk 36`, package `cz.dias.dpmbwear`.
