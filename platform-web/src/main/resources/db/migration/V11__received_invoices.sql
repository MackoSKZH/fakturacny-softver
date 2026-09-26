-- Kniha dosslych faktur: e-faktury (UBL, povinne prijimanie od 2027) aj papierove/PDF faktury od dodavatelov.

CREATE TABLE received_invoice (
    id             bigserial PRIMARY KEY,
    doc_type       text           NOT NULL DEFAULT 'FAKTURA' CHECK (doc_type IN ('FAKTURA', 'DOBROPIS')),
    number         text           NOT NULL CHECK (length(trim(number)) > 0),
    supplier_name  text           NOT NULL CHECK (length(trim(supplier_name)) > 0),
    supplier_ico   text,
    supplier_dic   text,
    supplier_iban  text,
    issue_date     date           NOT NULL,
    due_date       date,
    currency       char(3)        NOT NULL DEFAULT 'EUR',
    total_net      numeric(12, 2),
    total_vat      numeric(12, 2),
    total_payable  numeric(12, 2) NOT NULL CHECK (total_payable >= 0),
    payment_ref    text,          -- variabilny symbol / PaymentID pre parovanie s vypisom
    project_id     bigint REFERENCES project (id),
    category       text,
    note           text,
    source         text           NOT NULL CHECK (source IN ('UBL', 'RUCNE')),
    xml            text,          -- povodna e-faktura sa archivuje 10 rokov (zakon o uctovnictve)
    sha256         text,
    paid_on        date,
    created_by     text           NOT NULL,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    CHECK (due_date IS NULL OR due_date >= issue_date),
    CHECK ((source = 'UBL') = (xml IS NOT NULL))
);

-- Tu istu fakturu od toho isteho dodavatela nezapiseme dvakrat.
CREATE UNIQUE INDEX received_invoice_supplier_number_idx
    ON received_invoice (COALESCE(supplier_ico, lower(supplier_name)), number, doc_type);
CREATE UNIQUE INDEX received_invoice_sha_idx ON received_invoice (sha256) WHERE sha256 IS NOT NULL;

-- Uhrada dosslej faktury je vydavok v polozkach (so zamknutou sumou, ako pri vydanych fakturach).
ALTER TABLE ledger_entry ADD COLUMN received_invoice_id bigint REFERENCES received_invoice (id);
CREATE UNIQUE INDEX ledger_entry_received_invoice_idx ON ledger_entry (received_invoice_id)
    WHERE received_invoice_id IS NOT NULL;

-- PDF k dosslej fakture (papierova faktura, alebo vizualizacia e-faktury) ako priloha.
ALTER TABLE attachment ADD COLUMN received_invoice_id bigint REFERENCES received_invoice (id) ON DELETE CASCADE;
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
    WHERE conrelid = 'attachment'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%num_nonnulls%';
    EXECUTE format('ALTER TABLE attachment DROP CONSTRAINT %I', c);
END $$;
ALTER TABLE attachment ADD CONSTRAINT attachment_owner_check
    CHECK (num_nonnulls(project_id, partner_id, deal_id, received_invoice_id) = 1);
