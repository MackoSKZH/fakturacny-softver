-- Uctovne udaje obcianskeho zdruzenia. Vystavene doklady su nemenne (§ 35 zakona o uctovnictve:
-- archivacia 10 rokov) - opravy sa robia dobropisom, nie prepisanim.

CREATE TABLE organization (
    id                smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    name              text        NOT NULL,
    street            text,
    city              text,
    postal_code       text,
    country           char(2)     NOT NULL DEFAULT 'SK',
    ico               text,
    dic               text,
    ic_dph            text,
    email             text,
    phone             text,
    iban              text,
    bic               text,
    registration_note text,
    invoice_pattern   text        NOT NULL DEFAULT '{YYYY}{NNNN}',
    due_days          integer     NOT NULL DEFAULT 14 CHECK (due_days BETWEEN 0 AND 365),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE customer (
    id          bigserial PRIMARY KEY,
    name        text        NOT NULL,
    street      text,
    city        text,
    postal_code text,
    country     char(2)     NOT NULL DEFAULT 'SK',
    ico         text,
    dic         text,
    ic_dph      text,
    email       text,
    phone       text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id         bigserial PRIMARY KEY,
    code       text          NOT NULL UNIQUE,
    name       text          NOT NULL,
    budget     numeric(12, 2) NOT NULL DEFAULT 0 CHECK (budget >= 0),
    active     boolean       NOT NULL DEFAULT true,
    created_at timestamptz   NOT NULL DEFAULT now()
);

-- Ciselny rad: posledne pridelene poradove cislo pre vzor a rok. Zvysuje sa v tej istej
-- transakcii ako vznika faktura, takze pri chybe sa cislo nespotrebuje (ziadne medzery).
CREATE TABLE invoice_series (
    pattern     text    NOT NULL,
    year        integer NOT NULL,
    last_number bigint  NOT NULL DEFAULT 0 CHECK (last_number >= 0),
    PRIMARY KEY (pattern, year)
);

CREATE TABLE invoice (
    id            bigserial PRIMARY KEY,
    number        text           NOT NULL UNIQUE,
    issue_date    date           NOT NULL,
    due_date      date,
    customer_id   bigint         NOT NULL REFERENCES customer (id),
    project_id    bigint REFERENCES project (id),
    buyer_name    text           NOT NULL,
    total_net     numeric(12, 2) NOT NULL,
    total_vat     numeric(12, 2) NOT NULL,
    total_payable numeric(12, 2) NOT NULL,
    currency      char(3)        NOT NULL,
    document      jsonb          NOT NULL,
    ubl_xml       text,
    pdf           bytea          NOT NULL,
    issued_by     text           NOT NULL,
    issued_at     timestamptz    NOT NULL DEFAULT now(),
    paid_on       date
);

CREATE INDEX invoice_issue_date_idx ON invoice (issue_date);
CREATE INDEX invoice_project_idx ON invoice (project_id);

-- Poistka proti chybe v aplikacii: vystavenu fakturu nejde zmazat ani zmenit.
-- Menit sa smie iba datum uhrady.
CREATE FUNCTION invoice_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF tg_op = 'DELETE' THEN
        RAISE EXCEPTION 'Vystavenú faktúru % nie je možné zmazať, použite dobropis.', old.number;
    END IF;
    IF (new.number, new.issue_date, new.due_date, new.customer_id, new.project_id, new.buyer_name,
        new.total_net, new.total_vat, new.total_payable, new.currency, new.document, new.ubl_xml,
        new.pdf, new.issued_by, new.issued_at)
        IS DISTINCT FROM
       (old.number, old.issue_date, old.due_date, old.customer_id, old.project_id, old.buyer_name,
        old.total_net, old.total_vat, old.total_payable, old.currency, old.document, old.ubl_xml,
        old.pdf, old.issued_by, old.issued_at) THEN
        RAISE EXCEPTION 'Vystavená faktúra % je nemenná.', old.number;
    END IF;
    RETURN new;
END;
$$;

CREATE TRIGGER invoice_immutable
    BEFORE UPDATE OR DELETE ON invoice
    FOR EACH ROW EXECUTE FUNCTION invoice_guard();

CREATE TABLE audit_log (
    id        bigserial PRIMARY KEY,
    at        timestamptz NOT NULL DEFAULT now(),
    actor     text        NOT NULL,
    action    text        NOT NULL,
    entity    text        NOT NULL,
    entity_id text,
    detail    text
);

CREATE FUNCTION audit_log_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Audit log je iba na zápis.';
END;
$$;

CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();
