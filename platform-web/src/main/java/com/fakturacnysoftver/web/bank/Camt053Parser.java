package com.fakturacnysoftver.web.bank;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vypis z banky vo formate ISO 20022 camt.053 (XML) - vedia ho exportovat vsetky slovenske banky pre firemne
 * a spolkove ucty. Citame bez ohladu na verziu schemy (podla lokalnych nazvov elementov). XML je zo suboru
 * od pouzivatela, preto je vypnute DTD a externe entity (ochrana pred XXE).
 */
public final class Camt053Parser {
    private static final Pattern VS = Pattern.compile("(?i)/?VS[:\\s]*(\\d{1,10})");
    private static final Pattern SS = Pattern.compile("(?i)/SS[:\\s]*(\\d{1,10})");
    private static final Pattern KS = Pattern.compile("(?i)/KS[:\\s]*(\\d{1,4})");

    private Camt053Parser() {
    }

    public record Line(String ref, LocalDate date, BigDecimal amount, boolean credit, String currency,
                       String counterparty, String counterpartyIban, String vs, String ss, String ks,
                       String message) implements Serializable {
    }

    public record Statement(String iban, List<Line> lines, int skippedPending) implements Serializable {
    }

    public static Statement parse(byte[] xml) {
        Document doc;
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
            doc = b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new BankImportException("Súbor nie je platný XML výpis (camt.053). Stiahnite v internet bankingu "
                    + "výpis vo formáte XML / ISO 20022 / camt.053.");
        }
        Element stmt = first(doc.getDocumentElement(), "Stmt");
        if (stmt == null) {
            throw new BankImportException("V súbore nie je bankový výpis (chýba element Stmt formátu camt.053).");
        }
        String iban = text(first(first(stmt, "Acct"), "IBAN"));
        List<Line> lines = new ArrayList<>();
        int pending = 0;
        for (Element e : children(stmt, "Ntry")) {
            // <Sts>BOOK</Sts> (starsie verzie) aj <Sts><Cd>BOOK</Cd></Sts>; nezauctovane (PDNG) vynechame
            String status = text(first(e, "Sts"));
            if (status != null && !status.startsWith("BOOK")) {
                pending++;
                continue;
            }
            lines.add(line(e));
        }
        return new Statement(iban == null ? null : iban.replace(" ", ""), lines, pending);
    }

    private static Line line(Element e) {
        Element amt = first(e, "Amt");
        BigDecimal amount = new BigDecimal(text(amt).trim());
        String currency = amt.getAttribute("Ccy");
        boolean credit = "CRDT".equals(text(first(e, "CdtDbtInd")));
        Element date = first(e, "BookgDt");
        String d = text(first(date, "Dt"));
        if (d == null) {
            String dt = text(first(date, "DtTm"));
            d = dt == null ? null : dt.substring(0, 10);
        }
        Element tx = first(first(e, "NtryDtls"), "TxDtls");
        Element parties = first(tx, "RltdPties");
        Element other = first(parties, credit ? "Dbtr" : "Cdtr");
        Element otherAcct = first(parties, credit ? "DbtrAcct" : "CdtrAcct");
        String name = text(first(other, "Nm"));
        String iban = text(first(otherAcct, "IBAN"));
        String endToEnd = text(first(first(tx, "Refs"), "EndToEndId"));
        String ustrd = text(first(first(tx, "RmtInf"), "Ustrd"));
        String strdRef = text(first(first(first(first(tx, "RmtInf"), "Strd"), "CdtrRefInf"), "Ref"));
        String all = String.join(" ", nz(endToEnd), nz(strdRef), nz(ustrd));
        String vs = find(VS, all);
        if (vs == null && strdRef != null && strdRef.trim().matches("\\d{1,10}")) {
            vs = strdRef.trim();
        }
        String ref = text(first(e, "AcctSvcrRef"));
        if (ref == null) {
            ref = text(first(e, "NtryRef"));
        }
        if (ref == null) {
            ref = text(first(first(tx, "Refs"), "AcctSvcrRef"));
        }
        if (ref == null) {
            ref = "h:" + hash(d + "|" + amount.toPlainString() + "|" + credit + "|" + nz(iban) + "|" + nz(vs) + "|" + nz(ustrd));
        }
        return new Line(ref.trim(), d == null ? null : LocalDate.parse(d), amount, credit, currency,
                name == null ? null : name.trim(), iban == null ? null : iban.replace(" ", ""),
                vs == null ? null : vs.replaceFirst("^0+(?=\\d)", ""), find(SS, all), find(KS, all),
                ustrd == null ? null : ustrd.trim());
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static Element first(Element parent, String local) {
        if (parent == null) {
            return null;
        }
        NodeList all = parent.getElementsByTagNameNS("*", local);
        return all.getLength() == 0 ? null : (Element)all.item(0);
    }

    private static List<Element> children(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && local.equals(el.getLocalName())) {
                out.add(el);
            }
        }
        return out;
    }

    private static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
