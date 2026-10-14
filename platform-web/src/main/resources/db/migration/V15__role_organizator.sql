-- Rola Organizator: clen organizacneho timu, ktory pripravuje podujatia - cita a upravuje vsetky aktivity (tim,
-- harmonogram, ulohy), ale nevidi financie ani adresar ludi s osobnymi udajmi. Oprávnenia sa daju zmenit v Sprave.
INSERT INTO app_role (code, name, description, permissions, person_role, system) VALUES
('ORGANIZATOR', 'Organizátor', 'Pripravuje podujatia: číta a upravuje všetky aktivity, tím, harmonogram a úlohy. Financie ani adresár ľudí nevidí.',
 '{ACTIVITIES_READ,ACTIVITIES_WRITE}', 'ORGANIZATOR', true)
ON CONFLICT (code) DO NOTHING;
