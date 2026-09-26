# Nasadenie platformy

Cieľ: jeden server v EÚ, HTTPS, prihlásenie Google účtom, denné zálohy. Náklady rádovo 5 € mesačne.

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
- je lokálne prihlásenie bez hesla s aspoň 12 znakmi.

Po prvom prihlásení: **Nastavenia** (údaje OZ, IBAN, DIČ) -> **Odberatelia** -> **Projekty** -> **Nová faktúra**.

## 3. Zálohy - bez tohto do produkcie nechoďte

Kontajner `backup` robí denný `pg_dump` do `./backups` a drží 30 dní. **Záloha na tom istom serveri nie je záloha.** Nastavte kopírovanie mimo servera, napr. cez `rclone` do Google Drive OZ:

```sh
# crontab -e na serveri
30 3 * * * rclone copy /cesta/ucto/backups gdrive:ucto-zalohy --max-age 48h
```

Obnova (otestujte si ju aspoň raz, kým to nie je naostro):

```sh
docker compose exec -T db pg_restore -U ucto -d ucto --clean < backups/ucto-2027-01-15.dump
```

Účtovné doklady sa archivujú 10 rokov (§ 35 zákona o účtovníctve). Raz ročne si stiahnite všetky PDF a XML faktúry roka aj mimo platformy.

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
