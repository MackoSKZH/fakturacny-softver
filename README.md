# FIRST Global Slovakia HQ

Interná platforma občianskeho združenia FIRST Global Slovakia: aktivity (národné kolo, cesta na FGC, NTE, sústredenia, FLL turnaje), ľudia a ich roly, úlohy a checklisty, rozpočty, položky, faktúry a e-faktúry (Peppol, povinné od 1. 1. 2027).

| Modul | Čo robí |
|---|---|
| `platform-core` | Doména bez frameworku: faktúra, DPH 23/19/5 %, číselné rady, kontrola IČO/DIČ/IBAN, e-faktúra UBL 2.1 Peppol BIS 3.0, QR PAY by square |
| `platform-web` | Webová aplikácia (Spring Boot, PostgreSQL): aktivity, ľudia, tím a roly, úlohy, položky, faktúry a dobropisy, audit log, Google prihlásenie |
| `artifact/` | Prehliadačová ukážka fakturácie ako artifact na claude.ai |
| `src/` | Pôvodná desktopová aplikácia (JavaFX) - nahrádza ju `platform-web` |

- Rozsah platformy a čo ďalej: [docs/PLATFORMA.md](docs/PLATFORMA.md)
- Financie a e-fakturácia: [docs/PLAN.md](docs/PLAN.md)
- Nasadenie a zálohy: [docs/NASADENIE.md](docs/NASADENIE.md)

```sh
sh ./gradlew :platform-core:test :platform-web:test
```
