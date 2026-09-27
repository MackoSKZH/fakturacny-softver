-- Osobny odkaz na odber kalendara (ICS). Kalendarove aplikacie sa neprihlasuju, preto tajny token v URL.
-- Token sa da kedykolvek zrusit alebo vymenit; feed obsahuje len program danej osoby.
ALTER TABLE person ADD COLUMN calendar_token text;
CREATE UNIQUE INDEX person_calendar_token_idx ON person (calendar_token) WHERE calendar_token IS NOT NULL;
