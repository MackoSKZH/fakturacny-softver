package sk.firstglobal.hq.web;

import sk.firstglobal.hq.core.InvoiceLine;
import sk.firstglobal.hq.web.customer.Customer;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.invoice.InvoiceDraft;
import sk.firstglobal.hq.web.organization.Organization;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.project.ProjectRepository;

import java.math.BigDecimal;
import java.util.List;

/** Fiktivne OZ, sponzor a projekt. */
public final class TestData {
    public static final String IBAN = "SK3112000000198742637541";

    private TestData() {
    }

    public static Organization civicAssociation() {
        return new Organization("Robotické združenie, o. z.", "Hlavná 1", "Bratislava", "81101", "SK",
                "12345678", "2120000000", "", "info@example.sk", "", IBAN, "TATRSKBX",
                "Registrované na MV SR pod č. VVS/1-900/90-00000", "{YYYY}{NNNN}", 14);
    }

    public static Customer sponsor() {
        return new Customer(null, "Sponzor s.r.o.", "Priemyselná 5", "Košice", "04001", "SK",
                "87654321", "2020000000", "SK2020000000", "fakturacie@sponzor.example", null);
    }

    public record Ids(long customerId, long projectId) {
    }

    public static Ids setUp(OrganizationRepository orgs, CustomerRepository customers, ProjectRepository projects) {
        orgs.save(civicAssociation());
        long customerId = customers.insert(sponsor());
        long projectId = projects.insert("FGC-2027", "FIRST Global Challenge 2027", new BigDecimal("12000.00"));
        return new Ids(customerId, projectId);
    }

    public static InvoiceDraft advertisingDraft(Ids ids) {
        return new InvoiceDraft(ids.customerId(), ids.projectId(), null, null, null, null,
                "Zmluva o reklame 3/2027", null,
                List.of(InvoiceLine.notSubjectToVat("Charitatívna reklama - logo na robote",
                        BigDecimal.ONE, new BigDecimal("1500.00"))));
    }
}
