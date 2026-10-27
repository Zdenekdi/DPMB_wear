# DPMB Wear

Aplikace pro Wear OS: odjezdy MHD v Brně, SMS jízdenky, QR kód z Brno iD.

---

## Stav dat — přečti první

**Aplikace momentálně běží na ukázkových datech.** Odjezdy si generuje sama a nejsou
skutečné.

KORDIS JMK v roce 2026 zavedl technická opatření, která k API `mapa.idsjmk.cz`
pouštějí pouze smluvní partnery. Dřívější verze klienta se vydávala za prohlížeč
(podvržený `User-Agent`, `Referer`, `Sec-Fetch-*`) — to už nefunguje a bylo
odstraněno.

### Až přijde přístupový klíč

1. Doplň jej do `local.properties` (soubor je v `.gitignore`, do repozitáře se nedostane):

   ```
   IDS_API_KEY=sem_patri_klic
   ```

2. Nic dalšího není potřeba. `DeparturesRepository.defaultSource()` podle vyplněného
   klíče sám přepne z `FakeDeparturesSource` na `IdsJmkDeparturesSource`.

3. Ověř způsob předání klíče. Kód počítá s query parametrem `?key=`. Kdyby ho
   KORDIS chtěl jinak (například hlavičkou), mění se **jediné místo** —
   rozhraní `IdsJmkApi` v `data/IdsJmkApi.kt`.

### Klíč a veřejná distribuce

Klíč je v APK, takže z něj jde dekompilací získat. Pro soukromé užití to nevadí.
Pokud aplikace někdy zamíří k veřejné distribuci, klíč musí přejít na server a
hodinky budou chodit přes proxy. Architektura je na to připravená: znamená to
jednu novou třídu implementující `DeparturesSource`, zbytek aplikace se nemění.

---

## Architektura

```
data/
  DeparturesSource        rozhraní — jediné místo, kde se rozhoduje o zdroji dat
  FakeDeparturesSource      ukázková data, když chybí klíč
  IdsJmkDeparturesSource    ostrý zdroj z API KORDIS JMK
  IdsJmkApi                 Retrofit + OkHttp s timeouty
  DeparturesRepository      cache a výběr zdroje
  StopsRepository           seznam zastávek, hledání nejbližší
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

Obě testované funkce berou čas jako parametr, aby šel v testu zafixovat.

---

## Co zbývá

**Blokuje reálný provoz**

- Uzavřít smlouvu s KORDIS JMK a doplnit klíč.
- Databáze zastávek. `StopsRepository` má natvrdo pět zastávek, Brno jich má
  přes tisíc. Cílově `stops.txt` z GTFS (data.brno.cz, aktualizace v neděli)
  načtený do Room s vyhledáním podle polohy.

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
