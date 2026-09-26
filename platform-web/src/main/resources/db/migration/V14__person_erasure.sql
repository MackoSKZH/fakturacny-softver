-- GDPR: anonymizacia osoby (cl. 17). Karta ostava kvoli statistikam a prepojeniam, osobne udaje zmiznu.
-- name_retained = meno ostalo, lebo k osobe patria mzdove podklady (dohody, vykazy), ktore sa musia uchovat.
ALTER TABLE person ADD COLUMN anonymized_at timestamptz;
ALTER TABLE person ADD COLUMN name_retained boolean NOT NULL DEFAULT false;

-- Audit log a historia poloziek ostavaju len na zapis. Jedina vynimka: pri anonymizacii smie aplikacia v tej istej
-- transakcii prepisat meno/e-mail v stlpcoch actor a detail (nastavi fgs.audit_redaction = on). Cas, akcia, zaznam
-- ani id sa zmenit nedaju a mazat sa neda vobec.
CREATE OR REPLACE FUNCTION audit_log_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'audit_log'
       AND current_setting('fgs.audit_redaction', true) = 'on'
       AND NEW.id = OLD.id AND NEW.at = OLD.at AND NEW.action = OLD.action AND NEW.entity = OLD.entity
       AND NEW.entity_id IS NOT DISTINCT FROM OLD.entity_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Audit log je iba na zápis.';
END;
$$;
