package com.fakturacnysoftver.core.ubl;

import com.fakturacnysoftver.core.Invoice;
import com.fakturacnysoftver.core.InvoiceLine;
import com.fakturacnysoftver.core.InvoiceTotals;
import com.fakturacnysoftver.core.Party;
import com.fakturacnysoftver.core.VatCategory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Generuje e-fakturu vo formate UBL 2.1 podla Peppol BIS Billing 3.0 (EN 16931).
 * Poradie elementov je dane XSD schemou UBL - nemenit ho.
 */
public final class UblInvoiceWriter {
    public static final String NS_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    public static final String NS_CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    public static final String NS_CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    private static final String XMLNS = "http://www.w3.org/2000/xmlns/";

    public static final String CUSTOMIZATION_ID =
            "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";
    public static final String PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    /** UNCL1001: 380 = obchodna faktura. */
    private static final String TYPE_COMMERCIAL_INVOICE = "380";
    /** UNCL4461: 58 = SEPA prevod. */
    private static final String PAYMENT_MEANS_SEPA = "58";

    private final Document doc;
    private final String currency;

    private UblInvoiceWriter(Document doc, String currency) {
        this.doc = doc;
        this.currency = currency;
    }

    public static String write(Invoice invoice) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().newDocument();
            new UblInvoiceWriter(doc, invoice.currency()).build(invoice);
            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IllegalStateException("Nepodarilo sa vytvoriť UBL XML", e);
        }
    }

    private void build(Invoice inv) {
        InvoiceTotals totals = inv.totals();
        boolean onlyNotSubject = inv.lines().stream().allMatch(l -> l.vatCategory() == VatCategory.NOT_SUBJECT);

        Element root = this.doc.createElementNS(NS_INVOICE, "Invoice");
        root.setAttributeNS(XMLNS, "xmlns:cac", NS_CAC);
        root.setAttributeNS(XMLNS, "xmlns:cbc", NS_CBC);
        this.doc.appendChild(root);

        this.cbc(root, "CustomizationID", CUSTOMIZATION_ID);
        this.cbc(root, "ProfileID", PROFILE_ID);
        this.cbc(root, "ID", inv.number());
        this.cbc(root, "IssueDate", date(inv.issueDate()));
        if (inv.dueDate() != null) {
            this.cbc(root, "DueDate", date(inv.dueDate()));
        }
        this.cbc(root, "InvoiceTypeCode", TYPE_COMMERCIAL_INVOICE);
        if (notBlank(inv.note())) {
            this.cbc(root, "Note", inv.note());
        }
        this.cbc(root, "DocumentCurrencyCode", this.currency);
        // Peppol vyzaduje BuyerReference alebo OrderReference (PEPPOL-EN16931-R003).
        this.cbc(root, "BuyerReference", notBlank(inv.buyerReference()) ? inv.buyerReference() : inv.number());
        if (notBlank(inv.projectCode())) {
            Element project = this.cac(root, "ProjectReference");
            this.cbc(project, "ID", inv.projectCode());
        }

        Element supplier = this.cac(root, "AccountingSupplierParty");
        this.party(supplier, inv.seller(), true, onlyNotSubject);
        Element customer = this.cac(root, "AccountingCustomerParty");
        this.party(customer, inv.buyer(), false, onlyNotSubject);

        if (inv.deliveryDate() != null) {
            Element delivery = this.cac(root, "Delivery");
            this.cbc(delivery, "ActualDeliveryDate", date(inv.deliveryDate()));
        }

        if (notBlank(inv.payeeIban())) {
            Element pm = this.cac(root, "PaymentMeans");
            this.cbc(pm, "PaymentMeansCode", PAYMENT_MEANS_SEPA);
            if (notBlank(inv.variableSymbol())) {
                this.cbc(pm, "PaymentID", inv.variableSymbol());
            }
            Element account = this.cac(pm, "PayeeFinancialAccount");
            this.cbc(account, "ID", inv.payeeIban());
            if (notBlank(inv.payeeBic())) {
                Element branch = this.cac(account, "FinancialInstitutionBranch");
                this.cbc(branch, "ID", inv.payeeBic());
            }
        }

        Element taxTotal = this.cac(root, "TaxTotal");
        this.amount(taxTotal, "TaxAmount", totals.vatAmount());
        for (InvoiceTotals.VatBreakdown b : totals.vatBreakdown()) {
            Element sub = this.cac(taxTotal, "TaxSubtotal");
            this.amount(sub, "TaxableAmount", b.taxableAmount());
            this.amount(sub, "TaxAmount", b.taxAmount());
            Element cat = this.cac(sub, "TaxCategory");
            this.cbc(cat, "ID", b.category().code());
            if (b.category().hasRate()) {
                this.cbc(cat, "Percent", plain(b.ratePercent()));
            }
            if (b.exemptionCode() != null) {
                this.cbc(cat, "TaxExemptionReasonCode", b.exemptionCode());
                this.cbc(cat, "TaxExemptionReason", b.exemptionReason());
            }
            this.taxScheme(cat, "VAT");
        }

        Element monetary = this.cac(root, "LegalMonetaryTotal");
        this.amount(monetary, "LineExtensionAmount", totals.lineExtensionAmount());
        this.amount(monetary, "TaxExclusiveAmount", totals.lineExtensionAmount());
        this.amount(monetary, "TaxInclusiveAmount", totals.amountWithVat());
        this.amount(monetary, "PayableAmount", totals.payableAmount());

        int lineNo = 1;
        for (InvoiceLine line : inv.lines()) {
            this.invoiceLine(root, lineNo++, line);
        }
    }

    private void party(Element wrapper, Party p, boolean isSeller, boolean onlyNotSubject) {
        Element party = this.cac(wrapper, "Party");
        if (p.peppolId() != null) {
            Element endpoint = this.cbc(party, "EndpointID", p.dic());
            endpoint.setAttribute("schemeID", Party.PEPPOL_SCHEME_SK_DIC);
        }

        Element address = this.cac(party, "PostalAddress");
        if (notBlank(p.street())) {
            this.cbc(address, "StreetName", p.street());
        }
        if (notBlank(p.city())) {
            this.cbc(address, "CityName", p.city());
        }
        if (notBlank(p.postalCode())) {
            this.cbc(address, "PostalZone", p.postalCode());
        }
        Element country = this.cac(address, "Country");
        this.cbc(country, "IdentificationCode", p.country());

        // Pri kategorii O (neplatitel DPH) nesmie faktura obsahovat IČ DPH dodavatela ani odberatela (BR-O-02).
        if (p.isVatRegistered() && !onlyNotSubject) {
            Element pts = this.cac(party, "PartyTaxScheme");
            this.cbc(pts, "CompanyID", p.icDph());
            this.taxScheme(pts, "VAT");
        }
        // DIČ dodavatela ako danove registracne cislo (BT-32).
        if (isSeller && notBlank(p.dic())) {
            Element pts = this.cac(party, "PartyTaxScheme");
            this.cbc(pts, "CompanyID", p.dic());
            this.taxScheme(pts, "TAX");
        }

        Element legal = this.cac(party, "PartyLegalEntity");
        this.cbc(legal, "RegistrationName", p.name());
        if (notBlank(p.ico())) {
            this.cbc(legal, "CompanyID", p.ico());
        }

        if (notBlank(p.email()) || notBlank(p.phone())) {
            Element contact = this.cac(party, "Contact");
            if (notBlank(p.phone())) {
                this.cbc(contact, "Telephone", p.phone());
            }
            if (notBlank(p.email())) {
                this.cbc(contact, "ElectronicMail", p.email());
            }
        }
    }

    private void invoiceLine(Element root, int lineNo, InvoiceLine line) {
        Element il = this.cac(root, "InvoiceLine");
        this.cbc(il, "ID", String.valueOf(lineNo));
        Element qty = this.cbc(il, "InvoicedQuantity", plain(line.quantity()));
        qty.setAttribute("unitCode", line.unitCode());
        this.amount(il, "LineExtensionAmount", line.netAmount());

        Element item = this.cac(il, "Item");
        this.cbc(item, "Name", line.description());
        Element cat = this.cac(item, "ClassifiedTaxCategory");
        this.cbc(cat, "ID", line.vatCategory().code());
        if (line.vatCategory().hasRate()) {
            this.cbc(cat, "Percent", plain(line.vatRate()));
        }
        this.taxScheme(cat, "VAT");

        Element price = this.cac(il, "Price");
        this.amount(price, "PriceAmount", line.unitPrice());
    }

    private void taxScheme(Element parent, String id) {
        Element scheme = this.cac(parent, "TaxScheme");
        this.cbc(scheme, "ID", id);
    }

    private Element amount(Element parent, String name, BigDecimal value) {
        Element el = this.cbc(parent, name, value.toPlainString());
        el.setAttribute("currencyID", this.currency);
        return el;
    }

    private Element cac(Element parent, String name) {
        Element el = this.doc.createElementNS(NS_CAC, "cac:" + name);
        parent.appendChild(el);
        return el;
    }

    private Element cbc(Element parent, String name, String text) {
        Element el = this.doc.createElementNS(NS_CBC, "cbc:" + name);
        el.setTextContent(text);
        parent.appendChild(el);
        return el;
    }

    private static String date(java.time.LocalDate d) {
        return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String serialize(Document doc) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }
}
