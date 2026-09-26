-- Aktivity (akcie), ludia, obsadenie roli a ulohy. Aktivita je rozsireny projekt - drzi aj rozpocet a polozky.

ALTER TABLE project ADD COLUMN kind text NOT NULL DEFAULT 'INE'
    CHECK (kind IN ('NARODNE_KOLO', 'CESTA', 'NTE', 'SUSTREDENIE', 'FLL_TURNAJ', 'WORKSHOP', 'INE'));
ALTER TABLE project ADD COLUMN status text NOT NULL DEFAULT 'PRIPRAVA'
    CHECK (status IN ('PRIPRAVA', 'PREBIEHA', 'UKONCENA', 'ZRUSENA'));
ALTER TABLE project ADD COLUMN starts_on date;
ALTER TABLE project ADD COLUMN ends_on date;
ALTER TABLE project ADD COLUMN location text;
ALTER TABLE project ADD COLUMN description text;
ALTER TABLE project ADD CONSTRAINT project_dates CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on);

-- Jedna karta osoby naprieč vsetkymi akciami. Suhlasy podla GDPR / zakona 18/2018:
-- do 16 rokov suhlas dava zakonny zastupca, fotografie deti len so suhlasom zastupcu.
CREATE TABLE person (
    id                  bigserial PRIMARY KEY,
    full_name           text        NOT NULL CHECK (length(trim(full_name)) > 0),
    email               text,
    phone               text,
    organization        text,
    roles               text[]      NOT NULL DEFAULT '{}',
    is_minor            boolean     NOT NULL DEFAULT false,
    guardian_name       text,
    guardian_contact    text,
    data_consent_on     date,
    consent_by_guardian boolean     NOT NULL DEFAULT false,
    photo_consent       boolean     NOT NULL DEFAULT false,
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX person_email_idx ON person (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX person_roles_idx ON person USING gin (roles);

-- Rola / smena na aktivite, napr. "Rozhodca" 9:00-13:00, potrebni 3.
CREATE TABLE activity_role (
    id          bigserial PRIMARY KEY,
    project_id  bigint  NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name        text    NOT NULL CHECK (length(trim(name)) > 0),
    starts_at   timestamp,
    ends_at     timestamp,
    needed      integer NOT NULL DEFAULT 1 CHECK (needed BETWEEN 1 AND 500),
    description text,
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX activity_role_project_idx ON activity_role (project_id);

CREATE TABLE assignment (
    id        bigserial PRIMARY KEY,
    role_id   bigint NOT NULL REFERENCES activity_role (id) ON DELETE CASCADE,
    person_id bigint NOT NULL REFERENCES person (id),
    status    text   NOT NULL DEFAULT 'POZVANY' CHECK (status IN ('POZVANY', 'POTVRDENY', 'ODMIETOL', 'ZUCASTNIL_SA')),
    hours     numeric(5, 2) CHECK (hours IS NULL OR (hours >= 0 AND hours <= 200)),
    note      text,
    UNIQUE (role_id, person_id)
);

CREATE INDEX assignment_person_idx ON assignment (person_id);

CREATE TABLE task (
    id                 bigserial PRIMARY KEY,
    project_id         bigint      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    section            text        NOT NULL DEFAULT 'Úlohy',
    title              text        NOT NULL CHECK (length(trim(title)) > 0),
    due_on             date,
    assignee_person_id bigint REFERENCES person (id),
    done               boolean     NOT NULL DEFAULT false,
    done_at            timestamptz,
    done_by            text,
    position           integer     NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX task_project_idx ON task (project_id);

-- Sablony checklistov: termin = zaciatok aktivity + offset_days (zaporne = pred akciou).
CREATE TABLE checklist_item_template (
    id          bigserial PRIMARY KEY,
    kind        text    NOT NULL,
    section     text    NOT NULL,
    title       text    NOT NULL,
    offset_days integer NOT NULL,
    position    integer NOT NULL
);

INSERT INTO checklist_item_template (kind, section, title, offset_days, position) VALUES
('NARODNE_KOLO', 'Príprava', 'Rezervovať priestory - kapacita, elektrina, Wi-Fi, parkovanie', -150, 1),
('NARODNE_KOLO', 'Príprava', 'Otvoriť registráciu tímov', -120, 2),
('NARODNE_KOLO', 'Príprava', 'Zverejniť pravidlá a hárky k výzve v slovenčine', -100, 3),
('NARODNE_KOLO', 'Príprava', 'Uzavrieť registráciu a potvrdiť tímy', -85, 4),
('NARODNE_KOLO', 'Príprava', 'Rozoslať tímom súťažné sady a diely', -80, 5),
('NARODNE_KOLO', 'Ľudia', 'Zostaviť tím rozhodcov a hodnotiteľov', -45, 10),
('NARODNE_KOLO', 'Ľudia', 'Nábor dobrovoľníkov (registrácia, technická podpora, moderátor)', -40, 11),
('NARODNE_KOLO', 'Ľudia', 'Zmluvy o dobrovoľníckej činnosti a poučenie o rizikách (maloletí cez zákonného zástupcu)', -14, 12),
('NARODNE_KOLO', 'Ľudia', 'Školenie rozhodcov k pravidlám', -7, 13),
('NARODNE_KOLO', 'Ľudia', 'Rozoslať dobrovoľníkom harmonogram a pokyny', -5, 14),
('NARODNE_KOLO', 'Deti a GDPR', 'Zbierať súhlasy rodičov so spracovaním údajov a fotografiami', -21, 20),
('NARODNE_KOLO', 'Deti a GDPR', 'Poistenie zodpovednosti organizátora', -30, 21),
('NARODNE_KOLO', 'Logistika', 'Diplomy, medaily a ceny', -14, 30),
('NARODNE_KOLO', 'Logistika', 'Strava, pitný režim a zoznam alergií', -10, 31),
('NARODNE_KOLO', 'Logistika', 'Ozvučenie, projektor, stream', -3, 32),
('NARODNE_KOLO', 'Logistika', 'Postaviť a otestovať ihrisko', -1, 33),
('NARODNE_KOLO', 'Partneri', 'Potvrdiť protiplnenia sponzorom (logá, banner, zmienky)', -20, 40),
('NARODNE_KOLO', 'Partneri', 'Tlačová správa a pozvánky pre médiá', -7, 41),
('NARODNE_KOLO', 'Po akcii', 'Zverejniť výsledky a nominovaný tím', 1, 50),
('NARODNE_KOLO', 'Po akcii', 'Poďakovať dobrovoľníkom, vydať potvrdenia o dobrovoľníckej činnosti', 7, 51),
('NARODNE_KOLO', 'Po akcii', 'Spätná väzba a poučenia pre ďalší ročník', 14, 52),
('NARODNE_KOLO', 'Po akcii', 'Vyúčtovanie a správa pre sponzorov', 30, 53),

('CESTA', 'Tím', 'Nominovať študentov a mentorov', -150, 1),
('CESTA', 'Tím', 'Rozpočet cesty a plán financovania', -120, 2),
('CESTA', 'Doklady', 'Skontrolovať platnosť pasov (aspoň 6 mesiacov po návrate)', -120, 10),
('CESTA', 'Doklady', 'Víza alebo elektronické cestovné povolenia podľa krajiny', -100, 11),
('CESTA', 'Doklady', 'Písomný súhlas zákonných zástupcov s cestou do zahraničia', -90, 12),
('CESTA', 'Doprava a ubytovanie', 'Letenky', -90, 20),
('CESTA', 'Doprava a ubytovanie', 'Ubytovanie', -80, 21),
('CESTA', 'Doprava a ubytovanie', 'Preprava robota a batérií (pravidlá aerolinky pre Li-ion, clo mimo EÚ)', -45, 22),
('CESTA', 'Bezpečnosť', 'Cestovné poistenie vrátane liečebných nákladov', -30, 30),
('CESTA', 'Bezpečnosť', 'Zdravotné informácie a alergie - len nevyhnutné, s výslovným súhlasom', -20, 31),
('CESTA', 'Bezpečnosť', 'Kontakty na rodičov a núdzový plán', -10, 32),
('CESTA', 'Prezentácia', 'Dresy, vlajka, prezentačné a partnerské materiály', -30, 40),
('CESTA', 'Po návrate', 'Vyúčtovanie cesty', 20, 50),
('CESTA', 'Po návrate', 'Správa a fotky pre partnerov (len so súhlasom)', 30, 51),

('NTE', 'Fáza 1', 'Vybrať tému a problém, ktorý riešime', -40, 1),
('NTE', 'Fáza 1', 'Scenár 2-minútového videa', -25, 2),
('NTE', 'Fáza 1', 'Natáčanie a strih videa', -14, 3),
('NTE', 'Fáza 1', 'Reklamný plagát (display advertisement)', -10, 4),
('NTE', 'Fáza 1', 'Odovzdať fázu 1 na portáli FIRST Global', 0, 5),
('NTE', 'Ďalšie fázy', 'Pripraviť fázu 2 (ak postúpime)', 30, 10),
('NTE', 'Ďalšie fázy', 'Nacvičiť prezentáciu a pitch pre fázu 3', 60, 11),

('SUSTREDENIE', 'Príprava', 'Termín, miesto a ubytovanie', -60, 1),
('SUSTREDENIE', 'Príprava', 'Prihlášky účastníkov a súhlasy rodičov', -30, 2),
('SUSTREDENIE', 'Príprava', 'Rozpočet a výber účastníckych poplatkov', -21, 3),
('SUSTREDENIE', 'Príprava', 'Program a harmonogram', -21, 4),
('SUSTREDENIE', 'Bezpečnosť', 'Dozor - dostatok dospelých na počet detí', -21, 10),
('SUSTREDENIE', 'Bezpečnosť', 'Poučenie o bezpečnosti v dielni a pri práci s náradím', 0, 11),
('SUSTREDENIE', 'Logistika', 'Doprava', -14, 20),
('SUSTREDENIE', 'Logistika', 'Strava a alergie', -10, 21),
('SUSTREDENIE', 'Logistika', 'Materiál, náradie a náhradné diely', -7, 22),

('FLL_TURNAJ', 'Príprava', 'Dohodnúť s FLL Slovensko termín, rolu a povinné systémy (registrácia porotcov)', -120, 1),
('FLL_TURNAJ', 'Príprava', 'Priestory: ihriská, pit area, miestnosti pre porotu', -90, 2),
('FLL_TURNAJ', 'Ľudia', 'Nábor miestnych dobrovoľníkov', -45, 10),
('FLL_TURNAJ', 'Ľudia', 'Porotcovia a rozhodcovia cez oficiálnu registráciu FLL', -45, 11),
('FLL_TURNAJ', 'Ľudia', 'Harmonogram zápasov a porôt', -10, 12),
('FLL_TURNAJ', 'Deti a GDPR', 'Súhlasy rodičov s fotografiami', -14, 20),
('FLL_TURNAJ', 'Logistika', 'Strava a ceny', -10, 30),
('FLL_TURNAJ', 'Logistika', 'Časomiera, ozvučenie, projektor', -3, 31),
('FLL_TURNAJ', 'Po turnaji', 'Výsledky, poďakovania a potvrdenia dobrovoľníkom', 7, 40);
