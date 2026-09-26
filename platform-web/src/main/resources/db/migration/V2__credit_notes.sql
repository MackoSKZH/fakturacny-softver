-- Dobropisy (opravne faktury). Vlastny ciselny rad "D" + vzor faktur, odkaz na povodnu fakturu.
ALTER TABLE invoice ADD COLUMN doc_type text NOT NULL DEFAULT 'FAKTURA' CHECK (doc_type IN ('FAKTURA', 'DOBROPIS'));
ALTER TABLE invoice ADD COLUMN corrects_id bigint REFERENCES invoice (id);
ALTER TABLE invoice ADD COLUMN correction_reason text;
ALTER TABLE invoice ADD CONSTRAINT invoice_credit_note_ref
    CHECK ((doc_type = 'DOBROPIS') = (corrects_id IS NOT NULL AND correction_reason IS NOT NULL));
CREATE INDEX invoice_corrects_idx ON invoice (corrects_id);

CREATE OR REPLACE FUNCTION invoice_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF tg_op = 'DELETE' THEN
        RAISE EXCEPTION 'Vystavený doklad % nie je možné zmazať, použite dobropis.', old.number;
    END IF;
    IF (new.number, new.issue_date, new.due_date, new.customer_id, new.project_id, new.buyer_name,
        new.total_net, new.total_vat, new.total_payable, new.currency, new.document, new.ubl_xml,
        new.pdf, new.issued_by, new.issued_at, new.doc_type, new.corrects_id, new.correction_reason)
        IS DISTINCT FROM
       (old.number, old.issue_date, old.due_date, old.customer_id, old.project_id, old.buyer_name,
        old.total_net, old.total_vat, old.total_payable, old.currency, old.document, old.ubl_xml,
        old.pdf, old.issued_by, old.issued_at, old.doc_type, old.corrects_id, old.correction_reason) THEN
        RAISE EXCEPTION 'Vystavený doklad % je nemenný.', old.number;
    END IF;
    RETURN new;
END;
$$;
