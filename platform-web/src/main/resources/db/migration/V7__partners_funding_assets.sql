-- Partneri (sponzori, nadacie, granty, investori, jednotlivci), financovanie aktivit a majetok.

CREATE TABLE partner (
    id              bigserial PRIMARY KEY,
    name            text        NOT NULL CHECK (length(trim(name)) > 0),
    kind            text        NOT NULL DEFAULT 'FIRMA'
        CHECK (kind IN ('FIRMA', 'NADACIA', 'VEREJNY', 'SKOLA', 'JEDNOTLIVEC', 'INE')),
    ico             text,
    web             text,
    contact_name    text,
    contact_email   text,
    contact_phone   text,
    customer_id     bigint REFERENCES customer (id),
    owner_person_id bigint REFERENCES person (id),
    tags            text[]      NOT NULL DEFAULT '{}',
    note            text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX partner_name_idx ON partner (lower(name));

-- Dohoda o financovani: dar, reklama (faktura), grant, vecne plnenie, investicia.
CREATE TABLE deal (
    id              bigserial PRIMARY KEY,
    partner_id      bigint         NOT NULL REFERENCES partner (id),
    project_id      bigint REFERENCES project (id),
    title           text           NOT NULL CHECK (length(trim(title)) > 0),
    kind            text           NOT NULL CHECK (kind IN ('DAR', 'REKLAMA', 'GRANT', 'VECNE', 'INVESTICIA', 'INE')),
    stage           text           NOT NULL DEFAULT 'OSLOVENY'
        CHECK (stage IN ('OSLOVENY', 'ROKUJEME', 'DOHODNUTE', 'ZAPLATENE', 'ODMIETNUTE')),
    amount          numeric(12, 2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    expected_on     date,
    next_step       text,
    next_step_on    date,
    -- grant
    program         text,
    applied_on      date,
    period_from     date,
    period_to       date,
    report_due_on   date,
    reported_on     date,
    note            text,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    CHECK (period_to IS NULL OR period_from IS NULL OR period_to >= period_from)
);

CREATE INDEX deal_partner_idx ON deal (partner_id);
CREATE INDEX deal_project_idx ON deal (project_id);

-- Protiplnenia: logo na robote, banner, zmienka na sietach, sprava pre partnera...
CREATE TABLE deal_deliverable (
    id      bigserial PRIMARY KEY,
    deal_id bigint      NOT NULL REFERENCES deal (id) ON DELETE CASCADE,
    title   text        NOT NULL CHECK (length(trim(title)) > 0),
    due_on  date,
    done    boolean     NOT NULL DEFAULT false,
    done_at timestamptz,
    done_by text
);

CREATE INDEX deal_deliverable_deal_idx ON deal_deliverable (deal_id);

-- Historia komunikacie s partnerom (kto, kedy, co sa dohodlo).
CREATE TABLE partner_note (
    id          bigserial PRIMARY KEY,
    partner_id  bigint      NOT NULL REFERENCES partner (id) ON DELETE CASCADE,
    happened_on date        NOT NULL,
    text        text        NOT NULL CHECK (length(trim(text)) > 0),
    author      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX partner_note_partner_idx ON partner_note (partner_id, happened_on DESC);

-- Prijem od partnera alebo vydavok hradeny z grantu. Zmena prepojenia sa zapise do historie poloziek.
-- Dohodu s prepojenymi polozkami nejde zmazat (RESTRICT) - vyuctovanie grantu nesmie potichu zmiznut.
ALTER TABLE ledger_entry ADD COLUMN deal_id bigint REFERENCES deal (id);
CREATE INDEX ledger_entry_deal_idx ON ledger_entry (deal_id);

-- Majetok a hardver: robotické sady, notebooky, naradie. Stav "pozicane" vyplyva z otvorenej vypozicky.
CREATE TABLE asset (
    id              bigserial PRIMARY KEY,
    inventory_no    text           NOT NULL UNIQUE,
    name            text           NOT NULL CHECK (length(trim(name)) > 0),
    category        text           NOT NULL DEFAULT 'INE'
        CHECK (category IN ('ROBOTIKA', 'POCITAC', 'NARADIE', 'DIELY', 'PREZENTACIA', 'INE')),
    serial_no       text,
    purchased_on    date,
    price           numeric(12, 2) CHECK (price IS NULL OR price >= 0),
    ledger_entry_id bigint REFERENCES ledger_entry (id) ON DELETE SET NULL,
    deal_id         bigint REFERENCES deal (id) ON DELETE SET NULL,
    keep_until      date,
    location        text,
    status          text           NOT NULL DEFAULT 'AKTIVNY' CHECK (status IN ('AKTIVNY', 'OPRAVA', 'VYRADENY')),
    retired_on      date,
    retired_reason  text,
    note            text,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    CHECK ((status = 'VYRADENY') = (retired_on IS NOT NULL))
);

CREATE TABLE asset_loan (
    id          bigserial PRIMARY KEY,
    asset_id    bigint NOT NULL REFERENCES asset (id),
    person_id   bigint NOT NULL REFERENCES person (id),
    project_id  bigint REFERENCES project (id),
    lent_on     date   NOT NULL,
    due_on      date,
    returned_on date,
    note        text,
    lent_by     text   NOT NULL,
    CHECK (due_on IS NULL OR due_on >= lent_on),
    CHECK (returned_on IS NULL OR returned_on >= lent_on)
);

-- Jedna vec moze byt naraz pozicana len jednemu cloveku.
CREATE UNIQUE INDEX asset_loan_open_idx ON asset_loan (asset_id) WHERE returned_on IS NULL;
