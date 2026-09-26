# Nasadenie platformy

Cieľ: jeden server v EÚ, HTTPS, prihlásenie Google účtom, denné zálohy. Náklady rádovo 5 € mesačne.

## 0. Vyskúšanie na vlastnom počítači (10 minút, bez servera)

Potrebujete [Docker Desktop](https://www.docker.com/products/docker-desktop/) a stiahnutý repozitár.

```sh
docker compose -f docker-compose.demo.yml up --build
```

Po hláške „Demo dáta sú pripravené“ otvorte <http://localhost:8080>:

| Účet | Heslo | Čo vidí |
|---|---|---|
| `pokladnik` | `demo-heslo-2027` | editor - všetko, môže meniť |
| `eva@demo.example` | `demo-heslo-2027` | členka a dobrovoľníčka - len čítanie, Môj program, požičaný hub |
| `jana@demo.example` | `demo-heslo-2027` | platená koordinátorka - Moja dochádzka |

Ukážkové dáta: národné kolo o 45 dní (tím, harmonogram, checklist, verejná stránka `/podpora/NK-DEMO`), minulé sústredenie
s potvrdeniami o dobrovoľníctve, grant s výdavkom mimo obdobia, sponzor, majetok, dochádzka. Dátumy sú vždy relatívne k dnešku.

- Dáta ostávajú medzi reštartmi. Všetko zmazať: `docker compose -f docker-compose.demo.yml down -v`.
- Demo je len pre tento počítač (port na 127.0.0.1), jedno spoločné heslo, bez HTTPS. **Skutočné údaje sem nedávajte.**

## 1. Čo potrebujete

- VPS v EÚ s Dockerom (napr. Hetzner, 2 GB RAM stačí).
- Doménu alebo subdoménu smerujúcu na IP servera (záznam A), napr. `ucto.firstglobal.sk`.
- Google OAuth klienta (ak má OZ Google Workspace, ideálne v ňom):
  1. [Google Cloud Console](https://console.cloud.google.com/) -> APIs & Services -> Credentials -> Create OAuth client ID -> Web application.
  2. Authorized redirect URI: `https://VASA-DOMENA/login/oauth2/code/google`.
  3. Client ID a secret si odložte do `.env`.

## 2. Spustenie

```sh
git clone <repozitar> ucto && cd ucto
cp .env.example .env      # vyplnte DOMAIN, DATABASE_PASSWORD, GOOGLE_*, APP_ALLOWED_EMAILS
docker compose up -d --build
docker compose logs -f app   # "Started UctoApplication" = bezi
```

Aplikácia **odmietne štart**, ak:
- je zapnuté Google prihlásenie bez `APP_ALLOWED_EMAILS` / `APP_ALLOWED_DOMAINS` (inak by sa prihlásil ktokoľvek s Google účtom),
- je Google prihlásenie bez `APP_EDITOR_EMAILS` (aspoň jeden účet, ktorý smie vystavovať doklady),
- je lokálne prihlásenie bez hesla s aspoň 12 znakmi.

Roly (stav dnes):

- **editor** (e-maily v `APP_EDITOR_EMAILS`) mení všetko: financie, ľudí, partnerov, dochádzku, exporty,
- **člen** (`APP_ALLOWED_EMAILS` alebo celá doména v `APP_ALLOWED_DOMAINS`, napr. Google Workspace OZ) číta aktivity, harmonogram,
  financovanie, položky, faktúry a majetok; nevidí ľudí, partnerov ani hromadné exporty,
- každý prihlásený vidí **Môj program**, **Moju dochádzku** a svoje potvrdenia - prepojenie je cez e-mail v karte osoby.

Zmena rolí dnes znamená upraviť `.env` a reštartovať aplikáciu (`docker compose up -d`). Správa používateľov a jemnejšie roly
priamo v aplikácii sú ďalší krok - bez nich neodporúčame pustiť dnu dobrovoľníkov (videli by financie).

Po prvom prihlásení: **Nastavenia** (údaje OZ, IBAN, DIČ) -> **Aktivity** -> **Ľudia** -> **Partneri**.

## 3. Zálohy - bez tohto do produkcie nechoďte

Všetko (údaje, faktúry, PDF, nahrané podklady) je v jednej databáze PostgreSQL na serveri, takže jedna záloha pokryje všetko.
Kontajner `backup` robí denný `pg_dump` do `./backups` a drží 30 dní. **Záloha na tom istom serveri nie je záloha.**

Záloha obsahuje osobné údaje vrátane detí, preto ide mimo servera **šifrovaná**, napr. `rclone` so šifrovaným úložiskom
(`rclone config` -> typ `crypt` nad Google Drive OZ; heslo k šifrovaniu majú aspoň dvaja ľudia z vedenia):

```sh
# crontab -e na serveri
30 3 * * * rclone copy /cesta/ucto/backups gdrive-crypt:fgs-hq-zalohy --max-age 48h
```

Obnova (otestujte si ju aspoň raz, kým to nie je naostro):

```sh
docker compose exec -T db pg_restore -U ucto -d ucto --clean < backups/ucto-2027-01-15.dump
```

Účtovné doklady sa archivujú 10 rokov (§ 35 zákona o účtovníctve). Raz ročne si stiahnite všetky PDF a XML faktúry roka aj mimo platformy.

## Kde sú dáta a čo s tým súvisí (GDPR)

- **Server**: VPS v EÚ, odporúčame Hetzner (Nemecko/Fínsko), CX22 alebo podobný, ~5 € mesačne. V ich konzole podpíšte
  zmluvu o spracúvaní osobných údajov (DPA/AVV) - evidujeme aj údaje maloletých.
- **Prihlásenie**: Google účty (ideálne Google Workspace for Nonprofits - zadarmo). Heslá neukladáme, len zoznam povolených e-mailov.
- **Kód**: GitHub. V repozitári nie sú žiadne údaje ani heslá (`.env` sa necommituje).
- **Kto spravuje server**: aspoň dvaja ľudia s prístupom (SSH kľúč, heslo k zálohám), inak je to jeden bod zlyhania.
- Aktualizácie systému: na serveri zapnite `unattended-upgrades`.

## 4. Aktualizácia

```sh
git pull && docker compose up -d --build
```

Databázové migrácie (Flyway) sa spustia samé. Vystavené faktúry sa nemenia - databáza ich chráni triggerom.

## 5. Lokálny vývoj

```sh
# PostgreSQL 16 lokálne (alebo: docker compose up -d db)
DATABASE_PASSWORD=... APP_LOCAL_PASSWORD=dlhe-lokalne-heslo COOKIE_SECURE=false \
  sh ./gradlew :platform-web:bootRun
# http://localhost:8080, používateľ admin

# Testy - spustia vlastný embedded PostgreSQL (netreba Docker; nesmú bežať ako root)
sh ./gradlew :platform-core:test :platform-web:test
# ...alebo proti existujúcej DB:
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/ucto_test TEST_DATABASE_USER=ucto \
  TEST_DATABASE_PASSWORD=ucto sh ./gradlew :platform-web:test
```
