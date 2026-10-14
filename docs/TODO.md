# TODO

Zapisujeme sem veci, na ktorých sme sa dohodli, ale ešte nie sú hotové. Hotové veci presúvame do `docs/PLATFORMA.md`.

## 1. Pozvánky e-mailom cez Resend (priorita)

Dnes admin alebo vlastník aktivity vytvorí pozvánku a **odkaz si musí sám skopírovať a poslať**. Chceme: stlačím „Pozvať
človeka", vyberiem rolu (s prehľadom jej oprávnení, ako na Discorde), zadám e-mail a **pozvánka s odkazom mu príde mailom**
(„tu ťa čakáme, pozývame ťa srdečne...").

Čo na to treba:
- Odosielanie cez Resend API (`POST https://api.resend.com/emails`, kľúč v `RESEND_API_KEY`, odosielateľ napr. `hq@firstglobal.sk`).
  Appka dnes **žiadny e-mail neposiela**.
- **Overená doména** `firstglobal.sk` v Resende: v DNS na Websupporte pridať záznamy SPF a DKIM (+ DMARC). Bez nich e-maily
  končia v spame. Bezplatný plán Resendu (100 e-mailov denne, 3 000 mesačne) na pozvánky stačí.
- Pri vytváraní pozvánky na konkrétny e-mail políčko „Poslať e-mailom" (predvolene zapnuté). Pozvánka ostane vytvorená, aj keď
  odoslanie zlyhá (potom zobraziť odkaz na skopírovanie ako dnes).
- Otvorený odkaz (bez e-mailu) sa mailom poslať nedá - nemá adresáta.
- Znova odoslať stratenú pozvánku: v databáze je len odtlačok tokenu, takže „odoslať znova" = zrušiť a vytvoriť novú.
- Slovenský text e-mailu: kto pozýva, na čo (rola a čo smie, aktivita a smena), do kedy platí, tlačidlo „Prijať pozvánku".
- Zápis do auditu (kto koho pozval a kedy), limit odoslaní (ochrana pred zneužitím), token sa nesmie dostať do logov.
- Resend je americká firma - doplniť do záznamu o spracovateľských činnostiach (spracúva sa e-mail a meno pozvaného).
- Súvisí: výber role s prehľadom oprávnení priamo vo formulári pozvánky (dnes sú oprávnenia na karte roly v Správe).

## 2. Generátor memoranda o spolupráci

PDF z karty partnera (názov, IČO sa vezmú z partnera; právna forma, sídlo, oblasti spolupráce a záväzky sa vyplnia vo
formulári). Štruktúra podľa vzoru `Memorandum_template_FIRST_Global_Slovakia.docx`: zmluvné strany, preambula, predmet,
záväzky strán, finančné podmienky, kontaktné osoby, platnosť a ukončenie, záverečné ustanovenia, podpisy.

## 3. Podklad na výročnú správu

Nie plný Word generátor (vzor má fotky, logá a vlastné fonty). Na konci roka vygenerovať **podklad s číslami**: zoznam aktivít,
počty dobrovoľníkov a odpracovaných hodín, partneri a dohody s výškou podpory, finančný súhrn z peňažného denníka. Text, fotky
a príbeh sa dopíšu do existujúceho Word vzoru. Pozor na povinnosť výkazu transparentnosti pri príjmoch nad 35 000 € (novela
109/2025 Z. z.) - overiť s účtovníkom, či sa týka OZ.

## 4. Nasadenie

- Oracle Cloud Always Free vo Frankfurte + `hq.firstglobal.sk` (DNS na Websupporte, záznam A). Postup je v konverzácii,
  doplniť do `docs/NASADENIE.md` (časť pre Oracle) a vyskúšať naostro.
- Šifrované zálohy na Google Drive OZ (`rclone`), vyskúšať obnovu.
- Registrácia OZ u digitálneho poštára na príjem e-faktúr (termín 1. 1. 2027).

## 5. Z testovania

Poznámky z ručného testovania (formát: kde / čo som spravil / čo som čakal / čo sa stalo / ako veľmi to vadí) sa zapisujú sem.
