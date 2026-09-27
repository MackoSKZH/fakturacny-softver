package sk.firstglobal.hq.web.invoice;

import sk.firstglobal.hq.core.InvoiceLine;
import sk.firstglobal.hq.core.VatCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Formular novej faktury. Cisla prijima aj s desatinnou ciarkou ("12,50"). */
public class InvoiceForm {
    private Long customerId;
    private Long projectId;
    private LocalDate issueDate;
    private LocalDate deliveryDate;
    private LocalDate dueDate;
    private String variableSymbol;
    private String buyerReference;
    private String note;
    private String reason;
    private List<LineForm> lines = new ArrayList<>();

    public static class LineForm {
        private String description;
        private String quantity = "1";
        private String unit = InvoiceLine.UNIT_PIECE;
        private String unitPrice;
        private String vat = "23";

        public String getDescription() {
            return this.description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getQuantity() {
            return this.quantity;
        }

        public void setQuantity(String quantity) {
            this.quantity = quantity;
        }

        public String getUnit() {
            return this.unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public String getUnitPrice() {
            return this.unitPrice;
        }

        public void setUnitPrice(String unitPrice) {
            this.unitPrice = unitPrice;
        }

        public String getVat() {
            return this.vat;
        }

        public void setVat(String vat) {
            this.vat = vat;
        }

        boolean isBlank() {
            return blank(this.description) && blank(this.unitPrice);
        }
    }

    public InvoiceDraft toDraft(boolean vatPayer, List<String> errors) {
        if (this.customerId == null) {
            errors.add("Vyberte odberateľa.");
        }
        List<InvoiceLine> parsed = this.parseLines(vatPayer, errors);
        return new InvoiceDraft(this.customerId == null ? 0 : this.customerId, this.projectId, this.issueDate,
                this.deliveryDate, this.dueDate, this.variableSymbol, this.buyerReference, this.note, parsed);
    }

    /** Polozky formulara; prazdne riadky preskoci. */
    public List<InvoiceLine> parseLines(boolean vatPayer, List<String> errors) {
        List<InvoiceLine> parsed = new ArrayList<>();
        int no = 0;
        for (LineForm l : this.lines) {
            if (l == null || l.isBlank()) {
                continue;
            }
            no++;
            BigDecimal qty = parse(l.quantity, "Položka " + no + ": neplatné množstvo.", errors);
            BigDecimal price = parse(l.unitPrice, "Položka " + no + ": neplatná cena.", errors);
            if (qty == null || price == null) {
                continue;
            }
            String unit = blank(l.unit) ? InvoiceLine.UNIT_PIECE : l.unit.trim();
            String description = l.description == null ? "" : l.description.trim();
            if (!vatPayer) {
                parsed.add(new InvoiceLine(description, qty, unit, price, VatCategory.NOT_SUBJECT, null));
            } else if ("E".equals(l.vat)) {
                parsed.add(new InvoiceLine(description, qty, unit, price, VatCategory.EXEMPT, BigDecimal.ZERO));
            } else {
                BigDecimal rate = parse(l.vat, "Položka " + no + ": neplatná sadzba DPH.", errors);
                parsed.add(new InvoiceLine(description, qty, unit, price, VatCategory.STANDARD, rate));
            }
        }
        if (no == 0) {
            errors.add("Pridajte aspoň jednu položku.");
        }
        return parsed;
    }

    /** Predvyplni riadky z vystaveneho dokladu (napr. pre dobropis). */
    public static InvoiceForm fromLines(List<InvoiceLine> source) {
        InvoiceForm f = new InvoiceForm();
        for (InvoiceLine l : source) {
            LineForm lf = new LineForm();
            lf.setDescription(l.description());
            lf.setQuantity(l.quantity().stripTrailingZeros().toPlainString().replace('.', ','));
            lf.setUnit(l.unitCode());
            lf.setUnitPrice(l.unitPrice().toPlainString().replace('.', ','));
            lf.setVat(l.vatCategory() == VatCategory.EXEMPT ? "E"
                    : l.vatRate() == null ? "23" : l.vatRate().stripTrailingZeros().toPlainString());
            f.getLines().add(lf);
        }
        return f;
    }

    static BigDecimal parse(String raw, String error, List<String> errors) {
        if (blank(raw)) {
            errors.add(error);
            return null;
        }
        try {
            return new BigDecimal(raw.replaceAll("[\\s\\u00A0]", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            errors.add(error);
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public Long getCustomerId() {
        return this.customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getProjectId() {
        return this.projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public LocalDate getIssueDate() {
        return this.issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getDeliveryDate() {
        return this.deliveryDate;
    }

    public void setDeliveryDate(LocalDate deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public LocalDate getDueDate() {
        return this.dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getVariableSymbol() {
        return this.variableSymbol;
    }

    public void setVariableSymbol(String variableSymbol) {
        this.variableSymbol = variableSymbol;
    }

    public String getBuyerReference() {
        return this.buyerReference;
    }

    public void setBuyerReference(String buyerReference) {
        this.buyerReference = buyerReference;
    }

    public String getNote() {
        return this.note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getReason() {
        return this.reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<LineForm> getLines() {
        return this.lines;
    }

    public void setLines(List<LineForm> lines) {
        this.lines = lines;
    }
}
