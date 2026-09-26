-- Polozky (penazny dennik): prijmy a vydavky s projektom a tagmi.
-- Kazda zmena sa zapise do historie triggerom (§ 35 zakona o uctovnictve: opravy musia byt preukazatelne).

CREATE TABLE ledger_entry (
    id             bigserial PRIMARY KEY,
    entry_date     date           NOT NULL,
    description    text           NOT NULL CHECK (length(trim(description)) > 0),
    direction      text           NOT NULL CHECK (direction IN ('PRIJEM', 'VYDAVOK')),
    amount         numeric(12, 2) NOT NULL CHECK (amount > 0),
    project_id     bigint REFERENCES project (id),
    tags           text[]         NOT NULL DEFAULT '{}',
    category       text,
    counterparty   text,
    document_ref   text,
    payment_method text           NOT NULL DEFAULT 'BANKA' CHECK (payment_method IN ('BANKA', 'POKLADNA')),
    note           text,
    invoice_id     bigint REFERENCES invoice (id),
    version        integer        NOT NULL DEFAULT 1,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entry_date_idx ON ledger_entry (entry_date);
CREATE INDEX ledger_entry_project_idx ON ledger_entry (project_id);
CREATE INDEX ledger_entry_tags_idx ON ledger_entry USING gin (tags);
CREATE UNIQUE INDEX ledger_entry_invoice_idx ON ledger_entry (invoice_id) WHERE invoice_id IS NOT NULL;

CREATE TABLE ledger_history (
    id       bigserial PRIMARY KEY,
    entry_id bigint      NOT NULL,
    at       timestamptz NOT NULL DEFAULT now(),
    actor    text        NOT NULL,
    op       text        NOT NULL,
    old_row  jsonb,
    new_row  jsonb
);

CREATE INDEX ledger_history_entry_idx ON ledger_history (entry_id);

-- Aplikacia nastavuje app.actor v transakcii (set_config(..., true)); inak sa zapise "db".
CREATE FUNCTION ledger_audit() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO ledger_history (entry_id, actor, op, old_row, new_row)
    VALUES (COALESCE(new.id, old.id),
            COALESCE(NULLIF(current_setting('app.actor', true), ''), 'db'),
            tg_op,
            CASE WHEN tg_op <> 'INSERT' THEN to_jsonb(old) END,
            CASE WHEN tg_op <> 'DELETE' THEN to_jsonb(new) END);
    RETURN COALESCE(new, old);
END;
$$;

CREATE TRIGGER ledger_entry_audit
    AFTER INSERT OR UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_audit();

CREATE TRIGGER ledger_history_immutable
    BEFORE UPDATE OR DELETE ON ledger_history
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();
