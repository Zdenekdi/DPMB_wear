# DPMB Wear

Aplikace pro Wear OS: odjezdy MHD v Brně, SMS jízdenky, QR kód z Brno iD.

---

## Stav dat — přečti první

**Architektonické pravidlo: s KORDIS JMK mluví jen telefon.** Hodinky se
ptají telefonu přes Bluetooth (Wearable Data Layer) a dostanou hotový,
minimální JSON. Telefon stahuje z české IP (mobilní data / Wi-Fi), takže
případné blokace serverových a zahraničních adres se ho netýkají.

```
KORDIS JMK ──GTFS (1× denně, Wi-Fi)──▶ telefon :mobile ──Data Layer (~1 kB)──▶ hodinky :app
           ──GTFS-RT (na dotaz)─────▶  jízdní řád v mmap,                       cache + offline
                                       odhad zpoždění
```

| Modul | Co dělá |
|---|---|
| `:shared` | čisté Kotlin jádro: formát zpráv, převod GTFS, čtení GTFS-RT, odhad zpoždění, odjezdy. Testy běží na JVM. |
| `:mobile` | aplikace pro telefon: stahuje a převádí jízdní řád, odpovídá na dotazy hodinek |
| `:app` | hodinky: UI, dlaždice, komplikace, cache |
| `server/` | volitelná záloha pro hodinky na Wi-Fi/LTE bez telefonu (Python) |

**Obě aplikace musí mít stejné `applicationId` (`cz.dias.dpmbwear`) a stejný
podpisový klíč**, jinak se přes Data Layer neuvidí. Debug buildy z jednoho
počítače to splňují samy.

### Zdroje dat

- Jízdní řád: `https://kordis-jmk.cz/gtfs/gtfs.zip` – telefon ho kontroluje
  jednou denně na Wi-Fi (`If-Modified-Since`), převede na binární soubor
  (~60 MB pro celý kraj) a pak ho jen mapuje do paměti.
- Živá data: `https://kordis-jmk.cz/gtfs/gtfsReal.dat` – jen na dotaz
  z hodinek, drží se 15 s.
- Licence CC BY 4.0 (podle projektů, které data používají – ověřit u KORDIS).
  Uvedení zdroje je pod seznamem odjezdů v hodinkách i v aplikaci na telefonu.

**Zpoždění je odhad.** Feed obsahuje polohy vozidel, ne zpoždění. Počítá se
z polohy mezi zastávkami a jízdního řádu; náskok se do času nepromítá.

### Payload telefon → hodinky

```json
{"v":1,"id":"a1b2","t":1793170000,"rt":12,
 "s":[["U100","Česká",49.19612,16.60621,45,
       [["1","Ečerova",1793170120,2],["6","Královo Pole",1793170300,null]]]]}
```

`t` čas telefonu, `rt` stáří živých dat (−1 = jen jízdní řád), `s` zastávky
`[id, název, lat, lon, vzdálenost m, odjezdy]`, odjezd `[linka, cíl, epoch,
zpoždění min | null]`. Časy jsou absolutní, takže uložená odpověď zůstává
pravdivá i offline – hodinky jen přepočítají minuty a zahodí odjeté spoje.
Tři zastávky po osmi odjezdech ≈ 1 kB.

### Offline a výpadky

Pořadí v `DeparturesRepository.load()`:

1. telefon (odpověď mladší než 20 s se použije znovu bez Bluetooth),
2. server, je-li nastavený `DPMB_SERVER_URL` (hodinky bez telefonu),
3. **poslední známá data z cache** – minuty posunuté o stáří, odjeté spoje
   zahozené, na obrazovce „Bez signálu · před X min“, nejvýš 30 min,
4. ukázková data (jen když nic z toho není) – obrazovka to přizná.

Když telefon nemá internet, odpoví podle jízdního řádu bez živých dat
(`rt: -1`) a u spojů nebude „LIVE“.

---

## Architektura

```
data/
  DeparturesSource        rozhraní zdroje odjezdů
  PhoneBridge               Data Layer: dotaz na telefon, čekání na odpověď, uložení
  PhoneDeparturesSource     odjezdy z odpovědi telefonu (hlavní zdroj)
  ServerDeparturesSource    vlastní server nad otevřenými daty KORDIS
  DpmbServerApi             Retrofit rozhraní serveru + ServerClient
  FakeDeparturesSource      ukázková data, když není server ani klíč
  IdsJmkDeparturesSource    API KORDIS JMK s klíčem (smluvní partneři)
  IdsJmkApi                 Retrofit + OkHttp s timeouty
  DeparturesRepository      cache a výběr zdroje
  StopsRepository           nejbližší zastávky (telefon → server → paměť → statické),
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

Jádro má testy na JVM (`./gradlew :shared:testDebugUnitTest`): převod GTFS,
noční spoje a změna času, čtení GTFS-RT proti feedům z oficiální knihovny,
odhad zpoždění, formát payloadu. Hodinky: `PhoneMappingTest` (rozdíl hodin
telefonu a hodinek, stárnutí uložené odpovědi). Server: `cd server && pytest`.

Obě testované funkce berou čas jako parametr, aby šel v testu zafixovat.

---

## Co zbývá

**Před ostrým provozem**

- Sestavit všechny tři moduly v Android Studiu a nainstalovat `:mobile` na
  telefon a `:app` na hodinky.
- V aplikaci na telefonu stáhnout jízdní řád a „Vyzkoušet odjezdy u mě“.
- Ověřit licenci dat přímo u KORDIS.
- Porovnat odhad zpoždění se skutečností na pár spojích.

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
