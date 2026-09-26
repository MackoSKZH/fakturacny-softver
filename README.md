# Fakturačný softvér -> Účto

Faktúry, e-faktúry (Peppol) a projektové rozpočty pre občianske združenie. Pripravené na povinnú e-fakturáciu od 1. 1. 2027.

| Modul | Čo robí |
|---|---|
| `platform-core` | Doména bez frameworku: faktúra, DPH 23/19/5 %, číselné rady, kontrola IČO/DIČ/IBAN, e-faktúra UBL 2.1 Peppol BIS 3.0, QR PAY by square |
| `platform-web` | Webová aplikácia (Spring Boot, PostgreSQL): vystavenie faktúr, PDF, odberatelia, projekty, audit log, Google prihlásenie |
| `src/` | Pôvodná desktopová aplikácia (JavaFX) - nahrádza ju `platform-web` |

- Prečo a čo ďalej: [docs/PLAN.md](docs/PLAN.md)
- Nasadenie a zálohy: [docs/NASADENIE.md](docs/NASADENIE.md)

```sh
sh ./gradlew :platform-core:test :platform-web:test
```
