-- Dochadzka platenych ludi a harmonogram podujati.
-- Platforma eviduje cas a odmenu (hodiny x hodinovka); odvody a vyplatu robi uctovnik.

CREATE TABLE work_contract (
    id              bigserial PRIMARY KEY,
    person_id       bigint        NOT NULL REFERENCES person (id),
    kind            text          NOT NULL CHECK (kind IN ('DOVP', 'DOPC', 'DOBPS', 'PRACOVNY_POMER', 'ZIVNOST')),
    title           text          NOT NULL CHECK (length(trim(title)) > 0),
    job_description text,
    hourly_rate     numeric(8, 2) NOT NULL CHECK (hourly_rate > 0),
    valid_from      date          NOT NULL,
    valid_to        date          NOT NULL,
    project_id      bigint REFERENCES project (id),
    note            text,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CHECK (valid_to >= valid_from)
);

CREATE INDEX work_contract_person_idx ON work_contract (person_id);

CREATE TABLE work_log (
    id          bigserial PRIMARY KEY,
    contract_id bigint        NOT NULL REFERENCES work_contract (id),
    work_date   date          NOT NULL,
    hours       numeric(4, 2) NOT NULL CHECK (hours > 0 AND hours <= 12),
    project_id  bigint REFERENCES project (id),
    description text          NOT NULL CHECK (length(trim(description)) > 0),
    created_by  text          NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX work_log_contract_date_idx ON work_log (contract_id, work_date);

-- Uzavrety mesiac = podklad pre uctovnika, uz sa nemeni.
CREATE TABLE timesheet_close (
    contract_id bigint      NOT NULL REFERENCES work_contract (id),
    month       date        NOT NULL CHECK (extract(day FROM month) = 1),
    total_hours numeric(6, 2) NOT NULL,
    reward      numeric(10, 2) NOT NULL,
    closed_by   text        NOT NULL,
    closed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (contract_id, month)
);

CREATE FUNCTION work_log_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES (old.contract_id, old.work_date), (new.contract_id, new.work_date)) AS v(c, d)
             WHERE c IS NOT NULL
    LOOP
        IF EXISTS (SELECT 1 FROM timesheet_close t WHERE t.contract_id = r.c AND t.month = date_trunc('month', r.d)::date) THEN
            RAISE EXCEPTION 'Mesiac % je uzavretý - výkaz sa už nemení.', to_char(r.d, 'MM/YYYY');
        END IF;
    END LOOP;
    RETURN COALESCE(new, old);
END;
$$;

CREATE TRIGGER work_log_closed_month
    BEFORE INSERT OR UPDATE OR DELETE ON work_log
    FOR EACH ROW EXECUTE FUNCTION work_log_guard();

-- Harmonogram (program) podujatia: bod programu s vlastnikom a tagmi (pre koho je - roly, skupiny).
CREATE TABLE agenda_item (
    id               bigserial PRIMARY KEY,
    project_id       bigint    NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    starts_at        timestamp NOT NULL,
    ends_at          timestamp,
    title            text      NOT NULL CHECK (length(trim(title)) > 0),
    location         text,
    owner_person_id  bigint REFERENCES person (id),
    tags             text[]    NOT NULL DEFAULT '{}',
    note             text,
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX agenda_item_project_idx ON agenda_item (project_id, starts_at);
