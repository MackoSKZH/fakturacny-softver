-- Verejna stranka "Podporte nas" pre aktivitu: QR platba s VS aktivity. Zapina editor, predvolene vypnuta.
ALTER TABLE project ADD COLUMN donations_public boolean NOT NULL DEFAULT false;
ALTER TABLE project ADD COLUMN donation_text text;
ALTER TABLE project ADD COLUMN donation_goal numeric(12, 2) CHECK (donation_goal IS NULL OR donation_goal > 0);

-- Podklady: harky k vyzvam, pravidla, hodnotiace harky, zmluvy, fotky. Subor patri prave jednej veci.
-- Ulozene v databaze, takze ich pokryva ta ista zaloha (pg_dump) ako vsetko ostatne.
CREATE TABLE attachment (
    id           bigserial PRIMARY KEY,
    project_id   bigint REFERENCES project (id) ON DELETE CASCADE,
    partner_id   bigint REFERENCES partner (id) ON DELETE CASCADE,
    deal_id      bigint REFERENCES deal (id) ON DELETE CASCADE,
    category     text        NOT NULL CHECK (category IN ('VYZVA', 'PRAVIDLA', 'HODNOTENIE', 'ZMLUVA', 'DOKLAD', 'FOTO', 'INE')),
    file_name    text        NOT NULL CHECK (length(file_name) BETWEEN 1 AND 200),
    content_type text        NOT NULL,
    size_bytes   integer     NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    sha256       text        NOT NULL,
    content      bytea       NOT NULL,
    editors_only boolean     NOT NULL DEFAULT false,
    note         text,
    uploaded_by  text        NOT NULL,
    uploaded_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(project_id, partner_id, deal_id) = 1),
    -- Zmluvy obsahuju osobne a obchodne udaje - vzdy len pre editorov.
    CHECK (category <> 'ZMLUVA' OR editors_only)
);

CREATE INDEX attachment_project_idx ON attachment (project_id);
CREATE INDEX attachment_partner_idx ON attachment (partner_id);
CREATE INDEX attachment_deal_idx ON attachment (deal_id);
