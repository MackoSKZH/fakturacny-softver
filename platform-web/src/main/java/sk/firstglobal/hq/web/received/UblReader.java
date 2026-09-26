package sk.firstglobal.hq.web.received;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Cita e-fakturu UBL 2.1 (Peppol BIS Billing 3.0) - Invoice aj CreditNote. Prijimat e-faktury musi od 1. 1. 2027
 * aj obcianske zdruzenie. XML je od cudzieho odosielatela, preto je DTD a externe entity vypnute (XXE).
 */
public final class UblReader {
    private UblReader() {
    }

    public record Parsed(boolean creditNote, String number, LocalDate issueDate, LocalDate dueDate, String currency,
                         String supplierName, String supplierIco, String supplierDic, String supplierIban,
                         String buyerName, String buyerIco, BigDecimal totalNet, BigDecimal totalVat,
                         BigDecimal totalPayable, String paymentRef) {
    }

    public static Parsed read(byte[] xml) {
        Element root = parse(xml).getDocumentElement();
        String type = root.getLocalName();
        if (!"Invoice".equals(type) && !"CreditNote".equals(type)) {
            throw new ReceivedInvoiceException("Súbor nie je e-faktúra UBL (Invoice alebo CreditNote), ale " + type + ".");
        }
        boolean credit = "CreditNote".equals(type);
        Element supplier = child(child(root, "AccountingSupplierParty"), "Party");
        Element buyer = child(child(root, "AccountingCustomerParty"), "Party");
        Element totals = child(root, "LegalMonetaryTotal");
        Element means = child(root, "PaymentMeans");
        String number = text(child(root, "ID"));
        LocalDate issue = date(text(child(root, "IssueDate")), "dátum vyhotovenia");
        String due = text(child(root, "DueDate"));
        if (due == null) {
            due = text(child(means, "PaymentDueDate"));
        }
        BigDecimal payable = amount(text(child(totals, "PayableAmount")));
        if (number == null || issue == null || payable == null || supplier == null) {
            throw new ReceivedInvoiceException("E-faktúre chýba číslo, dátum, dodávateľ alebo suma na úhradu.");
        }
        return new Parsed(credit, number, issue, date(due, "dátum splatnosti"),
                orDefault(text(child(root, "DocumentCurrencyCode")), "EUR"),
                name(supplier), legalId(supplier), taxId(supplier),
                strip(text(child(child(means, "PayeeFinancialAccount"), "ID"))),
                name(buyer), legalId(buyer), amount(text(child(totals, "TaxExclusiveAmount"))),
                amount(text(child(child(root, "TaxTotal"), "TaxAmount"))), payable,
                text(child(means, "PaymentID")));
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new ReceivedInvoiceException("Súbor nie je platné XML e-faktúry.");
        }
    }

    private static String name(Element party) {
        String legal = text(child(child(party, "PartyLegalEntity"), "RegistrationName"));
        return legal != null ? legal : text(child(child(party, "PartyName"), "Name"));
    }

    /** IČO: PartyLegalEntity/CompanyID (8 cislic); pri inom formate ho nechame prazdne. */
    private static String legalId(Element party) {
        String id = strip(text(child(child(party, "PartyLegalEntity"), "CompanyID")));
        return id != null && id.matches("\\d{6,8}") ? id : null;
    }

    private static String taxId(Element party) {
        return strip(text(child(child(party, "PartyTaxScheme"), "CompanyID")));
    }

    private static Element child(Element parent, String local) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && local.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static BigDecimal amount(String s) {
        try {
            return s == null ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ReceivedInvoiceException("Suma „" + s + "“ v e-faktúre nie je číslo.");
        }
    }

    private static LocalDate date(String s, String label) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ReceivedInvoiceException("Neplatný " + label + " „" + s + "“.");
        }
    }

    private static String strip(String s) {
        return s == null ? null : s.replace(" ", "");
    }

    private static String orDefault(String s, String d) {
        return s == null ? d : s;
    }
}
