-- Pouzivatelia, roly (balicky opravneni), vlastnictvo aktivit a pozvanky.
-- Heslo neukladame nikdy: prihlasuje Google (alebo lokalny ucet na skusanie), tu je len kto co smie.

CREATE TABLE app_role (
    id          bigserial PRIMARY KEY,
    code        text        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{2,40}$'),
    name        text        NOT NULL CHECK (length(trim(name)) > 0),
    description text,
    permissions text[]      NOT NULL DEFAULT '{}',
    person_role text,       -- rola v adresari ludi, ktora sa prida pri prijati pozvanky (napr. DOBROVOLNIK)
    system      boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO app_role (code, name, description, permissions, person_role, system) VALUES
('ADMIN', 'Admin', 'Všetko vrátane používateľov, rolí a nastavení.', '{ADMIN}', NULL, true),
('FINANCIE', 'Financie (pokladník)', 'Položky, faktúry, financovanie, partneri, dochádzka a exporty.',
 '{ACTIVITIES_READ,FINANCE_READ,FINANCE_WRITE,PARTNERS,TIMESHEETS,EXPORTS}', NULL, true),
('KOORDINATOR', 'Koordinátor', 'Všetky aktivity, tím, harmonogram a adresár ľudí. Financie nevidí.',
 '{ACTIVITIES_READ,ACTIVITIES_WRITE,PEOPLE,ASSETS}', 'ORGANIZATOR', true),
('PROJEKTOVY_MANAZER', 'Projektový manažér', 'Číta aktivity, upravuje tie, ktoré vlastní, a vidí ich rozpočet.',
 '{ACTIVITIES_READ,ASSETS}', 'ORGANIZATOR', true),
('VEDENIE', 'Vedenie / rada', 'Číta aktivity a financie. Nič nemení.',
 '{ACTIVITIES_READ,FINANCE_READ}', NULL, true),
('MENTOR', 'Mentor', 'Číta aktivity a harmonogramy.', '{ACTIVITIES_READ}', 'MENTOR', true),
('DOBROVOLNIK', 'Dobrovoľník', 'Len Môj program, Moja dochádzka a potvrdenia.', '{}', 'DOBROVOLNIK', true);

CREATE TABLE app_user (
    id            bigserial PRIMARY KEY,
    -- prihlasovacia identita: e-mail z Google (alebo lokalne meno uctu na skusanie)
    email         text        NOT NULL CHECK (email = lower(trim(email)) AND length(email) > 1),
    display_name  text,
    person_id     bigint REFERENCES person (id) ON DELETE SET NULL,
    active        boolean     NOT NULL DEFAULT true,
    created_by    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz
);

CREATE UNIQUE INDEX app_user_email_idx ON app_user (email);

CREATE TABLE user_role (
    user_id bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id bigint NOT NULL REFERENCES app_role (id),
    PRIMARY KEY (user_id, role_id)
);

-- Vlastnik aktivity (projektovy manazer): upravuje ju aj bez celoplosneho opravnenia na aktivity.
CREATE TABLE activity_owner (
    user_id    bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    project_id bigint      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    added_by   text        NOT NULL,
    added_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, project_id)
);

-- Pozvanka: ukladame len SHA-256 tokenu, samotny odkaz sa ukaze raz pri vytvoreni.
CREATE TABLE invite (
    id          bigserial PRIMARY KEY,
    token_hash  text        NOT NULL UNIQUE,
    email       text CHECK (email IS NULL OR email = lower(trim(email))),
    role_ids    bigint[]    NOT NULL DEFAULT '{}',
    project_id  bigint REFERENCES project (id) ON DELETE CASCADE,
    note        text,
    max_uses    integer     NOT NULL DEFAULT 1 CHECK (max_uses BETWEEN 1 AND 200),
    used_count  integer     NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (used_count <= max_uses),
    CHECK (email IS NULL OR max_uses = 1)
);

CREATE TABLE invite_use (
    invite_id bigint      NOT NULL REFERENCES invite (id) ON DELETE CASCADE,
    user_id   bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    used_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (invite_id, user_id)
);
