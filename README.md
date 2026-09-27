# FIRST Global Slovakia HQ

Interná platforma občianskeho združenia FIRST Global Slovakia - jedno miesto pre akcie, ľudí a peniaze:
národné kolo, cesta na FIRST Global Challenge, NTE, sústredenia a FLL turnaje.

| Oblasť | Čo vie |
|---|---|
| Aktivity | tím a roly, harmonogram, osobný program a kalendár (ICS), úlohy a checklisty zo šablón, podklady, vlastníci aktivít |
| Ľudia | adresár so súhlasmi (GDPR, maloletí), dochádzka platených (DoVP, DoPČ, DoBPŠ), potvrdenia o dobrovoľníctve |
| Financie | položky v štýle Notion, rozpočty aktivít, vydané faktúry a e-faktúry (Peppol UBL), prijaté faktúry a e-faktúry, import výpisu z banky, partneri, dohody a granty, verejné príspevky cez QR, majetok |
| Prístup | Google prihlásenie, roly a vlastné roly, pozvánky, audit, exporty do Excelu a PDF |

| Modul | Obsah |
|---|---|
| `platform-core` | doména bez frameworku: faktúra, DPH, číselné rady, IČO/DIČ/IBAN, e-faktúra UBL 2.1 Peppol BIS 3.0, QR PAY by square |
| `platform-web` | webová aplikácia (Java 21, Spring Boot, PostgreSQL) |

- Rozsah a čo ďalej: [docs/PLATFORMA.md](docs/PLATFORMA.md)
- Financie, e-fakturácia a právny rámec: [docs/PLAN.md](docs/PLAN.md)
- Nasadenie (vlastný VPS alebo Render blueprint `render.yaml`), zálohy, roly: [docs/NASADENIE.md](docs/NASADENIE.md)

## Vyskúšať (Docker Desktop)

```sh
docker compose -f docker-compose.demo.yml up --build
# http://localhost:8080  pokladnik / demo-heslo-2027 (admin); ďalšie demo účty sú v docs/NASADENIE.md
```

## Testy

```sh
sh ./gradlew :platform-core:test :platform-web:test
```
