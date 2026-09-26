# Online platforma pre FIRST Global Slovakia - plán a stress test

Stav k 26. 9. 2026. Právne fakty sú overené z verejných zdrojov (zoznam na konci), nie sú to právne rady. Pred ostrým nasadením to musí odobriť účtovník OZ.

---

## TL;DR - verdikt

1. **Nápad je dobrý, ale máš zlé poradie priorít.** Pre OZ, ktoré nie je platiteľ DPH, je od 1. 1. 2027 povinné **prijímať** e-faktúry, nie ich vystavovať. Tvoj softvér je dnes o vystavovaní. To, čo vás reálne ohrozuje, je prijímanie.
2. **Súlad so zákonom k 1. 1. 2027 nevyrieši tvoj softvér, lebo za 3 mesiace nebude produkčne spoľahlivý.** Vyrieši ho registrácia u bezplatného digitálneho poštára (0 € / mesiac na prijímanie). To je hodina práce, nie projekt.
3. **Nestavaj vlastný Peppol access point.** Akreditácia, bezpečnosť, audit - to nie je projekt pre OZ. Pripoj sa cez štandardné API **SAPI-SK** ku ktorémukoľvek certifikovanému poštárovi.
4. **Vlastná platforma má zmysel len ako vrstva navyše:** rozpočty projektov, prepojenie s Notion, sledovanie charitatívnej reklamy, peňažný denník a podklady pre závierku. To hotové fakturačné programy pre OZ nerobia dobre.
5. **Notion nikdy nesmie byť účtovná kniha.** Je to dashboard. Zdroj pravdy je databáza platformy.
6. **"Dostať softvér online" nie je migrácia, je to prepis.** Z 1 200 riadkov súčasného kódu je ~90 % JavaFX UI, ktoré sa na web nedá preniesť. Preniesť sa dá doména - tú som v tejto iterácii prepísal nanovo a správne (`platform-core`).

---

## 1. Čo hovorí zákon

### E-fakturácia (novela zákona o DPH č. 385/2025 Z. z.)

| Téma | Stav |
|---|---|
| Účinnosť | **1. 1. 2027** - schválené NR SR 9. 12. 2025, podpísané 16. 12. 2025 |
| Kto musí **vystavovať** e-faktúry | Platitelia DPH (§ 4) pri tuzemských B2B/B2G plneniach |
| Kto musí **prijímať** e-faktúry | Aj neplatitelia DPH vrátane **občianskych združení, nadácií, neziskoviek** |
| Formát | UBL 2.1 (alebo CII) podľa EN 16931, profil **Peppol BIS Billing 3.0** |
| Doručenie | Sieť Peppol cez certifikovaného "digitálneho poštára" (access point) |
| Identifikátor v Peppol | `0245:DIČ` (10-miestne DIČ) |
| DIČ pre OZ | Finančná správa prideľuje ~58 000 subjektom DIČ **automaticky** - skontrolujte, či ho FGS už má |
| Integrácia softvéru | Štandard **SAPI-SK** (OpenAPI 3.0) - jedno API pre všetkých poštárov |
| §7 / §7a registrovaní | Povinnosť sa na nich nevzťahuje do 30. 6. 2030 |
| Pokuty za reporting | do 10 000 €, pri opakovaní do 100 000 € |
| **Návrh** (LP/2026/282, zatiaľ nie zákon) | 3-mesačné obdobie bez pokút 1. 1. - 31. 3. 2027; povinnosť odberateľa hlásiť prijaté faktúry posunutá na 1. 7. 2030. Povinnosť vedieť prijímať ostáva od 1. 1. 2027 |

**Oprava tvojej premisy:** neexistuje samostatný "zákon o e-fakturácii". Je to novela zákona o DPH. A **nový zákon o účtovníctve od 2027 som v zdrojoch nenašiel** - ak máš info o ňom, pošli zdroj, nech nestaviame na fámach.

### Účtovníctvo OZ (zákon č. 431/2002 Z. z.)

- OZ môže viesť **jednoduché účtovníctvo (JÚ)** podľa § 9 ods. 2, ak **nevykonáva podnikateľskú činnosť** a jeho príjmy za predchádzajúce obdobie **nedosiahli 200 000 €**. Inak podvojné.
- JÚ neziskovej účtovnej jednotky = peňažný denník, kniha pohľadávok, kniha záväzkov, evidencia majetku, na konci roka Výkaz o príjmoch a výdavkoch + Výkaz o majetku a záväzkoch.
- Účtovné doklady a závierku treba archivovať **10 rokov** (§ 35). Z toho plynie požiadavka na platformu: dáta musia prežiť aj teba, aj hosting, aj koniec podpory knižnice.

### Charitatívna reklama - najväčšia príležitosť pre FGS

- Príjmy OZ z reklamy sú **oslobodené od dane z príjmov do 20 000 € ročne**, ak sa použijú na účely podľa § 50 ods. 5 zákona o dani z príjmov. Nemusíte sa nikde registrovať (na rozdiel od 2 % / 3 %).
- Sponzor si to dá do nákladov, vy mu vystavíte faktúru za reklamu (logo na robote, dres, web).
- **Platforma by mala strážiť limit 20 000 € a použitie týchto peňazí po projektoch.** Toto žiadny bežný fakturačný program nerobí.

### Pasca, na ktorú sa neziskovky zabúdajú - § 7a

Ak je OZ zdaniteľná osoba (napr. predáva reklamu) a kupuje **služby zo zahraničia** (Notion, Google, AWS, Canva...), môže mať povinnosť registrovať sa podľa § 7a a samozdaniť DPH. **Overiť s účtovníkom** - toto je reálne riziko pokuty, nie teória.

---

## 2. Brutálne úprimne: súčasný stav softvéru

| # | Problém | Dopad |
|---|---|---|
| 1 | Číslo faktúry = `dátum + currentTimeMillis % 100000` a generuje sa **pri každom renderi PDF** | Nie je poradové, môže sa zopakovať, to isté PDF exportované dvakrát má iné číslo. Porušenie § 74 ods. 1 písm. b) zákona o DPH (pre platiteľov) a nemožnosť dohľadať doklad |
| 2 | Faktúry sa **nikam neukladajú** | Žiadna kniha vystavených faktúr, žiadna archivácia 10 rokov |
| 3 | DPH natvrdo 23 % | Znížené sadzby 19 % a 5 % (od 2025) nejdú; oslobodené plnenia nejdú |
| 4 | Peniaze v `double` | Zaokrúhľovacie chyby |
| 5 | DPH sa počíta po položkách | EN 16931 počíta za skupinu sadzby - pri e-faktúre by validácia faktúru odmietla |
| 6 | Pole "Variabilný symbol" sa nikdy nečítalo | VS sa nedostal na PDF - **opravené v tejto iterácii** |
| 7 | Chýba dátum splatnosti | Prakticky nutný údaj |
| 8 | Veľa položiek pretečie mimo stranu | PDF nemá stránkovanie |
| 9 | `Updater` sťahuje ZIP bez podpisu a rozbaľuje bez kontroly ciest (zip-slip) | Vzdialené spustenie kódu, ak niekto podstrčí ZIP |
| 10 | Build má natvrdo Windows cesty (`C:/Program Files/...`) | Nikto iný to nezostaví |

Záver: ako desktopová pomôcka pre jednu osobu OK, ako účtovný systém organizácie nie.

---

## 3. Stress test tvojho nápadu

**"Presunieme to online."**
Online systém s účtovnými dátami organizácie = prihlasovanie, oprávnenia, zálohy, GDPR (osobné údaje členov a darcov), audit log, 10-ročná archivácia. Ak toto nechceš riešiť, **kúp hotový nástroj**. Ak to riešiť chceš, platforma musí mať tieto veci od prvého dňa, nie "neskôr".

**"Bude to na vedenie účtovníctva."**
Za účtovníctvo zodpovedá štatutár OZ, nie autor softvéru. Ak máte externého účtovníka, platforma má byť **zdroj podkladov pre neho** (export), nie náhrada. Ak účtujete sami, platforma musí vedieť JÚ výkazy - a vtedy ju musí aspoň raz skontrolovať účtovník.

**"Prepojíme to s Notion."**
Áno, ale jasne rozdelené vlastníctvo dát:
- Notion vlastní **plán**: projekty, rozpočtové riadky, zodpovedné osoby.
- Platforma vlastní **realitu**: faktúry, platby, doklady.
- Synchronizácia platforma -> Notion (transakcie s väzbou na projekt), Notion rollup ukáže čerpanie rozpočtu.
- Nikdy obojsmerná editácia tej istej veci - to je recept na nekonzistentné účtovníctvo.

**Bus factor.**
Čo sa stane, keď odídeš na vysokú? Preto: nudná technológia, jeden `docker compose up`, dokumentácia v repozitári, export všetkých dát do otvorených formátov (CSV, UBL XML, PDF). OZ musí vedieť z platformy kedykoľvek odísť.

**Build vs. buy - úprimne.**
Na čisté vystavovanie a prijímanie faktúr existujú hotové nástroje, niektoré zadarmo. Vlastná platforma sa oplatí len ak:
- chcete projektové rozpočty + Notion + sledovanie charitatívnej reklamy na jednom mieste, a
- je to zároveň vzdelávací / showcase projekt tímu (čo pre FIRST tím dáva zmysel).
Ak ani jedno neplatí, zastavte sa tu a kúpte si nástroj.

---

## 4. Navrhovaná architektúra

```
                +-------------------- platforma (EU hosting) --------------------+
 prehliadač --> | Spring Boot web (Thymeleaf + trochu vanilla JS)                |
 (Google login) |   |-- platform-core: faktúry, DPH, číslovanie, UBL  [HOTOVÉ]    |
                |   |-- PDF + PAY by square QR                         [HOTOVÉ]    |
                |   |-- peňažný denník (JÚ), kniha pohľadávok / záväzkov         |
                |   |-- projekty a rozpočty [ZÁKLAD], limit charit. reklamy       |
                |   +-- audit log, nemenné vystavené doklady [HOTOVÉ], dobropisy |
                |  PostgreSQL (+ nočné zálohy mimo servera)                      |
                +------+-----------------------+------------------------+--------+
                       | SAPI-SK (REST)        | Notion API             | banka (CSV / API)
                       v                       v                        v
              digitálny poštár (Peppol)   Notion: Projekty,       výpisy -> párovanie
              prijaté + odoslané e-faktúry   Transakcie (rollup)     platieb podľa VS
```

**Prečo Java / Spring Boot a nie prepis do JS:**
- Tím už vie Javu, PDFBox kód sa dá čiastočne použiť.
- Java má najlepší ekosystém pre e-faktúry (phive, ph-ubl - oficiálne Peppol validačné pravidlá). Už teraz ich používame v testoch.
- Server-rendered UI (Thymeleaf + ~60 riadkov vanilla JS) = jeden deploy, žiadny SPA build, žiadna JS knižnica na aktualizovanie.

**Hosting:** EU VPS (napr. Hetzner, rádovo 5 € / mesiac) + Docker Compose + šifrované nočné zálohy mimo servera. Overiť nárok na neziskové programy (Google for Nonprofits, TechSoup) - môžu dať Workspace a kredity zadarmo.

---

## 5. Roadmapa

### Fáza 0 - hneď, bez kódu (do 31. 12. 2026) - **toto je jediné, čo je naozaj urgentné**
- [ ] Overiť, či FGS má DIČ (FS ho prideľuje automaticky).
- [ ] Zaregistrovať OZ u digitálneho poštára s bezplatným prijímaním a s podporou SAPI-SK.
- [ ] S účtovníkom: JÚ alebo PÚ? Platiteľ DPH? Registrácia § 7a kvôli zahraničným službám?
- [ ] Rozhodnúť, kto je vlastník platformy po tvojom odchode.

### Fáza 1 - doménové jadro [HOTOVÉ v tejto iterácii]
- `platform-core`: faktúra, položky, `BigDecimal`, sadzby 23 / 19 / 5 %, kategórie DPH podľa EN 16931, poradové číslovanie radov, kontrola IČO / DIČ / IČ DPH / IBAN / VS, export **UBL 2.1 Peppol BIS 3.0**.
- Testy validujú XML **oficiálnymi pravidlami OpenPeppol (release 2026.05)** - XSD + EN 16931 + Peppol Schematron, vrátane negatívneho testu, ktorý dokazuje, že validácia reálne beží.

### Fáza 2 - web a vystavovanie [HOTOVÉ]
- `platform-web`: Spring Boot 4.1, PostgreSQL 16, Flyway, Thymeleaf. Stránky: faktúry, odberatelia, projekty, nastavenia + audit log.
- Prihlásenie Google účtom so zoznamom povolených e-mailov / domén; aplikácia sa bez neho **odmietne spustiť**. Lokálny režim len s heslom 12+ znakov. CSRF, CSP hlavičky, XSS escapovanie otestované.
- Vystavenie v jednej transakcii: kontrola -> pridelenie čísla (`UPDATE ... RETURNING` so zámkom) -> PDF + UBL -> uloženie. Otestované: 24 súbežných vystavení = čísla 1 až 24 bez medzier a duplicít; neúspešné vystavenie číslo nespotrebuje.
- Nemennosť: DB trigger zakáže zmenu alebo zmazanie vystavenej faktúry (povolená je len úhrada). Audit log je iba na zápis.
- Chronológia: nová faktúra nesmie mať dátum starší než posledná v roku ani dátum v budúcnosti.
- PDF: § 74 náležitosti, rekapitulácia DPH podľa sadzieb, stránkovanie dlhých faktúr, znaky mimo písma (emoji) PDF nezhodia.
- **PAY by square**: výstup je bajt po bajte zhodný s nezávislou implementáciou (Python `pay-by-square`), a test QR kód **naskenuje z vyrenderovaného PDF**. Pred ostrým použitím ho aj tak naskenujte v 2 až 3 bankových appkách.
- E-faktúra (UBL) sa uloží len keď odberateľ má DIČ (inak nie je kam doručiť) - vtedy len PDF.
- Nasadenie: `Dockerfile`, `docker-compose.yml` (PostgreSQL, HTTPS cez Caddy, denné zálohy), CI pre GitHub Actions. Návod: [NASADENIE.md](NASADENIE.md).
- **Neoverené:** samotný `docker compose up` (v tomto prostredí nebeží Docker daemon; overená je len konfigurácia a build cez gradle wrapper).

**Doplnené po fáze 2:**
- **Dobropis** (opravná faktúra): vlastný rad `D` + vzor faktúr, odkaz na pôvodnú faktúru a dôvod opravy v PDF aj v UBL `CreditNote` (prechádza oficiálnymi Peppol pravidlami pre dobropis). Súčet dobropisov nesmie prekročiť sumu faktúry, kontrola beží pod zámkom pôvodnej faktúry (otestované súbežnými dobropismi). Rozpočet projektu dobropisy odpočíta.
- **Roly:** editori (`APP_EDITOR_EMAILS`) vystavujú a menia, ostatní povolení členovia len čítajú. Bez aspoň jedného editora sa aplikácia v Google režime nespustí.
- **Artifact** na claude.ai (`artifact/`): prehliadačová verzia na vyskúšanie, rovnaká logika v JS, XML overené tými istými Peppol pravidlami.

**Čo ešte chýba:**
- Úprava a deaktivácia odberateľov a projektov.
- Fázy 3 až 6 nižšie. Na fázu 3 (SAPI-SK) treba oficiálnu OpenAPI špecifikáciu zo sapi-sk.sk.

### Fáza 3 - prijaté e-faktúry
- SAPI-SK klient: stiahnuť prijaté dokumenty, uložiť originál XML (archív), rozparsovať, priradiť k projektu, potvrdiť prijatie.

### Fáza 4 - peňažný denník a banka
- Import výpisov, párovanie podľa VS, peňažný denník JÚ, knihy pohľadávok a záväzkov.

### Fáza 5 - Notion
- Jednosmerný sync transakcií do Notion databázy s väzbou na projekt, čítanie rozpočtov z Notion.

### Fáza 6 - koniec roka
- Podklady pre Výkaz o príjmoch a výdavkoch a Výkaz o majetku a záväzkoch, prehľad charitatívnej reklamy vs. limit 20 000 €, export pre účtovníka.

---

## 6. Otvorené otázky - bez odpovedí na ne sa ďalej stavia naslepo

1. Je FGS platiteľ DPH? Registrované podľa § 7 / § 7a?
2. JÚ alebo PÚ? Kto dnes vedie účtovníctvo a v akom programe?
3. Aké príjmy máte (granty, dary, 2 % / 3 %, reklama, členské) a zhruba koľko dokladov ročne?
4. Máte Google Workspace alebo Microsoft 365 (kvôli prihlasovaniu)?
5. Kto bude platformu spravovať o 2 roky?

---

## Ako spustiť testy jadra

```
gradle :platform-core:test
```

## Zdroje

- [Povinná elektronická fakturácia od roku 2027 - EY](https://www.ey.com/sk_sk/services/tax/ey-global-tax-e-invoicing-solution-sk/povinna-elektronicka-fakturacia-od-roku-2027)
- [Slovakia: VAT e-invoicing implementation begins January 1, 2027 - KPMG](https://kpmg.com/us/en/taxnewsflash/news/2026/05/slovakia-vat-e-invoicing-implementation-2027.html)
- [E-faktúra v mimovládnych organizáciách od 1.1.2027 - Podnikajte.sk](https://www.podnikajte.sk/dan-z-pridanej-hodnoty/efaktura-v-mimovladnych-organizaciach-od-1-1-2027)
- [Mimovládne organizácie dostávajú DIČ - Netky.sk](https://www.netky.sk/clanok/mimovladne-organizacie-dostavaju-dic-od-roku-2027-ich-caka-povinna-efaktura)
- [Finančná správa - e-Faktúra](https://www.financnasprava.sk/sk/podnikatelia/dane/dan-z-pridanej-hodnoty/e-faktura)
- [Slovakia Proposes Delaying Buyer Reporting Until July 2030 - VATupdate](https://www.vatupdate.com/2026/09/24/slovakia-proposes-delaying-buyer-reporting-until-july-2030/)
- [Slovakia B2B e-invoicing & e-reporting 2027 - vatcalc.com](https://www.vatcalc.com/slovakia/slovakia-is-efa-e-invoice-proposal-2024-delay-to-b2g/)
- [SAPI-SK](https://www.sapi-sk.sk/)
- [Peppol Slovensko: Peppol ID - ePošťák](https://epostak.sk/blog/peppol-slovensko-sprievodca)
- [Občianske združenie a jednoduché účtovníctvo - Uctujto.sk](https://www.uctujto.sk/obcianske-zdruzenie-jednoduche-uctovnictvo/)
- [Aspekty občianskeho združenia - Najprávo.sk](https://www.najpravo.sk/ustavne-pravo/rady-a-vzory/rady-pre-kazdeho/o/aspekty-obcianskeho-zdruzenia-cast-2.html)
- [Charitatívna reklama a daň z príjmov - Podnikajte.sk](https://www.podnikajte.sk/dan-z-prijmov/charitativna-reklama)
- [Usmernenie FS k oslobodeniu príjmov z reklám](https://www.financnasprava.sk/_img/pfsedit/Dokumenty_PFS/Zverejnovanie_dok/Dane/Metodicke_usmernenia/Priame_dane/2024/2024.06.04_020_DZPaU_2024_MU.pdf)
