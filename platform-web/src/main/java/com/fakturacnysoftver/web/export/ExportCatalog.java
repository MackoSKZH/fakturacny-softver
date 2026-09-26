package com.fakturacnysoftver.web.export;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Vsetky prehlady na jednom mieste. Kazdy export je jeden SQL dotaz, ktoreho aliasy stlpcov su
 * zaroven hlavicka tabulky - novy export = novy riadok v zozname, nic dalsie.
 */
@Component
public class ExportCatalog {
    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    public record Export(String key, String label, String description, String sql) {
    }

    private static final String ACTIVITY_KIND = """
            CASE p.kind WHEN 'NARODNE_KOLO' THEN 'Národné kolo' WHEN 'CESTA' THEN 'Cesta na FGC'
                        WHEN 'NTE' THEN 'NTE' WHEN 'SUSTREDENIE' THEN 'Sústredenie' WHEN 'FLL_TURNAJ' THEN 'FLL turnaj'
                        WHEN 'WORKSHOP' THEN 'Workshop' ELSE 'Iné' END""";

    private static final List<Export> EXPORTS = List.of(
            new Export("aktivity", "Aktivity", "Rozpočet, čerpanie, príjmy, obsadenie a úlohy každej aktivity.", """
                    SELECT p.code AS "Kód", p.name AS "Názov", %s AS "Typ",
                           CASE p.status WHEN 'PREBIEHA' THEN 'Prebieha' WHEN 'UKONCENA' THEN 'Ukončená'
                                         WHEN 'ZRUSENA' THEN 'Zrušená' ELSE 'Príprava' END AS "Stav",
                           p.starts_on AS "Od", p.ends_on AS "Do", p.location AS "Miesto", p.budget AS "Rozpočet (€)",
                           COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.project_id = p.id
                                     AND l.direction = 'VYDAVOK'), 0) AS "Čerpanie (€)",
                           COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.project_id = p.id
                                     AND l.direction = 'PRIJEM'), 0) AS "Príjmy (€)",
                           (SELECT COALESCE(sum(needed), 0) FROM activity_role r WHERE r.project_id = p.id) AS "Potrebných ľudí",
                           (SELECT count(*) FROM assignment a JOIN activity_role r ON r.id = a.role_id
                            WHERE r.project_id = p.id AND a.status IN ('POTVRDENY', 'ZUCASTNIL_SA')) AS "Potvrdených",
                           (SELECT count(*) FROM task t WHERE t.project_id = p.id) AS "Úlohy",
                           (SELECT count(*) FROM task t WHERE t.project_id = p.id AND t.done) AS "Hotové",
                           (SELECT count(*) FROM task t WHERE t.project_id = p.id AND NOT t.done
                            AND t.due_on < :today) AS "Po termíne"
                    FROM project p ORDER BY p.starts_on DESC NULLS LAST, p.code""".formatted(ACTIVITY_KIND)),
            new Export("ulohy", "Úlohy a checklisty", "Všetky úlohy naprieč aktivitami s termínom a zodpovedným.", """
                    SELECT pr.code AS "Aktivita", t.section AS "Sekcia", t.title AS "Úloha", t.due_on AS "Termín",
                           p.full_name AS "Zodpovedný", CASE WHEN t.done THEN 'hotovo'
                                WHEN t.due_on < :today THEN 'PO TERMÍNE' ELSE 'otvorená' END AS "Stav",
                           t.done_by AS "Uzavrel"
                    FROM task t JOIN project pr ON pr.id = t.project_id LEFT JOIN person p ON p.id = t.assignee_person_id
                    ORDER BY pr.code, t.done, t.due_on NULLS LAST, t.position, t.id"""),
            new Export("obsadenie", "Tím a obsadenie", "Kto je v akej role na ktorej akcii, stav a odpracované hodiny.", """
                    SELECT pr.code AS "Aktivita", r.name AS "Rola", r.starts_at AS "Od", r.ends_at AS "Do",
                           p.full_name AS "Osoba",
                           CASE a.status WHEN 'POTVRDENY' THEN 'potvrdený' WHEN 'ODMIETOL' THEN 'odmietol'
                                         WHEN 'ZUCASTNIL_SA' THEN 'zúčastnil sa' ELSE 'pozvaný' END AS "Stav",
                           a.hours AS "Hodiny", CASE WHEN p.is_minor THEN 'áno' ELSE '' END AS "Maloletý"
                    FROM assignment a JOIN activity_role r ON r.id = a.role_id JOIN project pr ON pr.id = r.project_id
                         JOIN person p ON p.id = a.person_id
                    ORDER BY pr.code, r.starts_at NULLS FIRST, r.name, p.full_name"""),
            new Export("harmonogram", "Harmonogram všetkých akcií", "Body programu so zodpovednými a cieľovými skupinami.", """
                    SELECT pr.code AS "Aktivita", ai.starts_at AS "Začiatok", ai.ends_at AS "Koniec", ai.title AS "Bod programu",
                           COALESCE(ai.location, pr.location) AS "Miesto", o.full_name AS "Zodpovedá", ai.tags AS "Pre",
                           ai.note AS "Poznámka"
                    FROM agenda_item ai JOIN project pr ON pr.id = ai.project_id
                         LEFT JOIN person o ON o.id = ai.owner_person_id
                    ORDER BY ai.starts_at, pr.code"""),
            new Export("polozky", "Položky (príjmy a výdavky)", "Celá evidencia s tagmi, kategóriami a dokladmi.", """
                    SELECT l.entry_date AS "Dátum", l.description AS "Popis",
                           CASE l.direction WHEN 'PRIJEM' THEN 'Príjem' ELSE 'Výdavok' END AS "Typ", l.amount AS "Suma (€)",
                           pr.code AS "Projekt", l.tags AS "Tagy", l.category AS "Kategória", l.counterparty AS "Protistrana",
                           l.document_ref AS "Doklad", CASE l.payment_method WHEN 'POKLADNA' THEN 'Pokladňa' ELSE 'Banka' END
                           AS "Úhrada", l.note AS "Poznámka"
                    FROM ledger_entry l LEFT JOIN project pr ON pr.id = l.project_id
                    ORDER BY l.entry_date, l.id"""),
            new Export("faktury", "Faktúry a dobropisy", "Vydané doklady, sumy, splatnosť a úhrady.", """
                    SELECT i.number AS "Číslo", CASE i.doc_type WHEN 'DOBROPIS' THEN 'Dobropis' ELSE 'Faktúra' END AS "Doklad",
                           i.issue_date AS "Vystavená", i.due_date AS "Splatná", i.buyer_name AS "Odberateľ",
                           pr.code AS "Projekt", i.total_net AS "Základ (€)", i.total_vat AS "DPH (€)",
                           i.total_payable AS "Spolu", i.currency AS "Mena", i.paid_on AS "Uhradená",
                           c.number AS "Opravuje faktúru"
                    FROM invoice i LEFT JOIN project pr ON pr.id = i.project_id
                         LEFT JOIN invoice c ON c.id = i.corrects_id
                    ORDER BY i.issue_date, i.number"""),
            new Export("ludia", "Ľudia a súhlasy", "Kontakty, roly a stav súhlasov (GDPR). Obsahuje osobné údaje.", """
                    SELECT p.full_name AS "Meno", p.email AS "E-mail", p.phone AS "Telefón", p.organization AS "Organizácia",
                           p.roles AS "Roly", CASE WHEN p.is_minor THEN 'áno' ELSE '' END AS "Maloletý",
                           p.guardian_name AS "Zákonný zástupca", p.guardian_contact AS "Kontakt na zástupcu",
                           p.data_consent_on AS "Súhlas s údajmi", CASE WHEN p.photo_consent THEN 'áno' ELSE 'nie' END
                           AS "Súhlas s fotkami",
                           (SELECT sum(a.hours) FROM assignment a WHERE a.person_id = p.id
                            AND a.status = 'ZUCASTNIL_SA') AS "Dobrovoľnícke hodiny"
                    FROM person p ORDER BY p.full_name"""),
            new Export("zmluvy", "Zmluvy platených ľudí", "Dohody a zmluvy s náplňou práce a hodinovkou.", """
                    SELECT p.full_name AS "Meno", CASE c.kind WHEN 'DOVP' THEN 'Dohoda o vykonaní práce'
                               WHEN 'DOPC' THEN 'Dohoda o pracovnej činnosti' WHEN 'DOBPS' THEN 'Dohoda o brigádnickej práci študentov'
                               WHEN 'PRACOVNY_POMER' THEN 'Pracovný pomer' ELSE 'Živnosť / faktúra' END AS "Typ",
                           c.title AS "Pozícia", c.job_description AS "Náplň práce", c.hourly_rate AS "Hodinovka (€)",
                           c.valid_from AS "Od", c.valid_to AS "Do", pr.code AS "Projekt",
                           (SELECT sum(hours) FROM work_log w WHERE w.contract_id = c.id) AS "Hodín spolu"
                    FROM work_contract c JOIN person p ON p.id = c.person_id LEFT JOIN project pr ON pr.id = c.project_id
                    ORDER BY p.full_name, c.valid_from"""),
            new Export("hodiny", "Register hodín", "Každý zápis odpracovaných hodín.", """
                    SELECT w.work_date AS "Dátum", p.full_name AS "Meno", c.title AS "Pozícia", w.hours AS "Hodiny",
                           pr.code AS "Projekt", w.description AS "Činnosť", w.created_by AS "Zapísal",
                           CASE WHEN EXISTS (SELECT 1 FROM timesheet_close t WHERE t.contract_id = c.id
                                AND t.month = date_trunc('month', w.work_date)::date) THEN 'áno' ELSE 'nie' END AS "Uzavreté"
                    FROM work_log w JOIN work_contract c ON c.id = w.contract_id JOIN person p ON p.id = c.person_id
                         LEFT JOIN project pr ON pr.id = w.project_id
                    ORDER BY w.work_date, p.full_name"""),
            new Export("partneri", "Partneri", "Kontakty, kto vzťah vedie, dohodnuté a prijaté sumy, posledný kontakt.", """
                    SELECT p.name AS "Partner", CASE p.kind WHEN 'FIRMA' THEN 'Firma' WHEN 'NADACIA' THEN 'Nadácia / fond'
                               WHEN 'VEREJNY' THEN 'Verejný sektor' WHEN 'SKOLA' THEN 'Škola' WHEN 'JEDNOTLIVEC' THEN 'Jednotlivec'
                               ELSE 'Iné' END AS "Typ", p.ico AS "IČO", p.contact_name AS "Kontakt", p.contact_email AS "E-mail",
                           p.contact_phone AS "Telefón", o.full_name AS "Vzťah vedie", p.tags AS "Tagy",
                           COALESCE((SELECT sum(amount) FROM deal d WHERE d.partner_id = p.id
                                     AND d.stage IN ('DOHODNUTE', 'ZAPLATENE')), 0) AS "Dohodnuté (€)",
                           COALESCE((SELECT sum(l.amount) FROM ledger_entry l JOIN deal d ON d.id = l.deal_id
                                     WHERE d.partner_id = p.id AND l.direction = 'PRIJEM'), 0) AS "Prijaté (€)",
                           (SELECT max(happened_on) FROM partner_note n WHERE n.partner_id = p.id) AS "Posledný kontakt"
                    FROM partner p LEFT JOIN person o ON o.id = p.owner_person_id ORDER BY lower(p.name)"""),
            new Export("financovanie", "Financovanie a granty", "Všetky dohody: typ, stav, suma, prijaté, čerpanie grantov a termíny.", """
                    SELECT pa.name AS "Partner", d.title AS "Dohoda",
                           CASE d.kind WHEN 'DAR' THEN 'Dar' WHEN 'REKLAMA' THEN 'Reklama / sponzoring' WHEN 'GRANT' THEN 'Grant'
                               WHEN 'VECNE' THEN 'Vecné plnenie' WHEN 'INVESTICIA' THEN 'Investícia' ELSE 'Iné' END AS "Typ",
                           CASE d.stage WHEN 'OSLOVENY' THEN 'Oslovený' WHEN 'ROKUJEME' THEN 'Rokujeme' WHEN 'DOHODNUTE' THEN 'Dohodnuté'
                               WHEN 'ZAPLATENE' THEN 'Zaplatené' ELSE 'Odmietnuté' END AS "Stav",
                           pr.code AS "Aktivita", d.amount AS "Suma (€)",
                           COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.deal_id = d.id AND l.direction = 'PRIJEM'), 0)
                           AS "Prijaté (€)",
                           COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.deal_id = d.id AND l.direction = 'VYDAVOK'), 0)
                           AS "Čerpané (€)",
                           d.expected_on AS "Platba do", d.next_step AS "Ďalší krok", d.next_step_on AS "Termín kroku",
                           d.program AS "Program", d.period_from AS "Obdobie od", d.period_to AS "Obdobie do",
                           d.report_due_on AS "Vyúčtovanie do", d.reported_on AS "Vyúčtovanie odovzdané"
                    FROM deal d JOIN partner pa ON pa.id = d.partner_id LEFT JOIN project pr ON pr.id = d.project_id
                    ORDER BY pa.name, d.created_at"""),
            new Export("protiplnenia", "Protiplnenia partnerom", "Čo sme partnerom sľúbili, do kedy a či je to splnené.", """
                    SELECT pa.name AS "Partner", d.title AS "Dohoda", pr.code AS "Aktivita", x.title AS "Protiplnenie",
                           x.due_on AS "Termín", CASE WHEN x.done THEN 'splnené'
                               WHEN x.due_on < :today THEN 'PO TERMÍNE' ELSE 'otvorené' END AS "Stav", x.done_by AS "Splnil"
                    FROM deal_deliverable x JOIN deal d ON d.id = x.deal_id JOIN partner pa ON pa.id = d.partner_id
                         LEFT JOIN project pr ON pr.id = d.project_id
                    ORDER BY x.done, x.due_on NULLS LAST, pa.name"""),
            new Export("majetok", "Majetok a hardvér", "Inventár s cenou, dokladom, grantom, stavom a kto čo má požičané.", """
                    SELECT a.inventory_no AS "Inventárne číslo", a.name AS "Názov",
                           CASE a.category WHEN 'ROBOTIKA' THEN 'Robotika' WHEN 'POCITAC' THEN 'Počítače a tablety'
                               WHEN 'NARADIE' THEN 'Náradie' WHEN 'DIELY' THEN 'Diely' WHEN 'PREZENTACIA' THEN 'Prezentácia'
                               ELSE 'Iné' END AS "Kategória", a.serial_no AS "Sériové číslo", a.purchased_on AS "Kúpené",
                           a.price AS "Cena (€)", l.document_ref AS "Doklad", d.title AS "Grant", a.keep_until AS "Udržať do",
                           CASE WHEN a.status = 'VYRADENY' THEN 'vyradené' WHEN lo.id IS NOT NULL THEN 'požičané'
                               WHEN a.status = 'OPRAVA' THEN 'v oprave' ELSE 'k dispozícii' END AS "Stav",
                           a.location AS "Kde", p.full_name AS "Požičané komu", lo.due_on AS "Vrátiť do",
                           a.retired_on AS "Vyradené", a.retired_reason AS "Dôvod vyradenia"
                    FROM asset a LEFT JOIN ledger_entry l ON l.id = a.ledger_entry_id LEFT JOIN deal d ON d.id = a.deal_id
                         LEFT JOIN asset_loan lo ON lo.asset_id = a.id AND lo.returned_on IS NULL
                         LEFT JOIN person p ON p.id = lo.person_id
                    ORDER BY a.status = 'VYRADENY', a.category, a.inventory_no"""));

    private final JdbcClient jdbc;
    private final Clock clock;

    public ExportCatalog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<Export> all() {
        return EXPORTS;
    }

    public Optional<Export> find(String key) {
        return EXPORTS.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    public Table build(Export e) {
        JdbcClient.StatementSpec spec = this.jdbc.sql(e.sql());
        if (e.sql().contains(":today")) {
            spec = spec.param("today", LocalDate.now(this.clock));
        }
        return spec.query((ResultSetExtractor<Table>)rs -> {
            ResultSetMetaData md = rs.getMetaData();
            String[] header = new String[md.getColumnCount()];
            for (int i = 0; i < header.length; i++) {
                header[i] = md.getColumnLabel(i + 1);
            }
            Table t = new Table(e.label(), header);
            while (rs.next()) {
                Object[] row = new Object[header.length];
                for (int i = 0; i < header.length; i++) {
                    Object v = "timestamptz".equals(md.getColumnTypeName(i + 1))
                            ? rs.getObject(i + 1, OffsetDateTime.class) : rs.getObject(i + 1);
                    row[i] = switch (v) {
                        case null -> null;
                        case Date d -> d.toLocalDate();
                        case Timestamp ts -> ts.toLocalDateTime();
                        case OffsetDateTime odt -> odt.atZoneSameInstant(ZONE).toLocalDateTime();
                        case Array a -> new ArrayList<>(Arrays.asList((Object[])a.getArray()));
                        default -> v;
                    };
                }
                t.row(row);
            }
            return t;
        });
    }
}
