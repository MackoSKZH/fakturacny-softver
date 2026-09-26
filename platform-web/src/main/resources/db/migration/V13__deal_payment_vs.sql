-- Variabilny symbol dohody: predvolene 700000 + id (rozsah 700000-799999 je vyhradeny dohodam, 800000-899999 zbierkam
-- aktivit). Vlastny VS sa zada, ked grantor/sponzor plati so svojim cislom zmluvy.
ALTER TABLE deal ADD COLUMN payment_vs text CHECK (payment_vs IS NULL OR payment_vs ~ '^[1-9][0-9]{0,9}$');
CREATE UNIQUE INDEX deal_payment_vs_idx ON deal (payment_vs) WHERE payment_vs IS NOT NULL;
