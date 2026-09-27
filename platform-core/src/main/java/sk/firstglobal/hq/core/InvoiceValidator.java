package sk.firstglobal.hq.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Kontroly pred vystavenim faktury. Vracia zoznam chyb v slovencine; prazdny zoznam = OK.
 * Pravidla vychadzaju z § 74 zakona c. 222/2004 Z. z. o DPH, § 10 zakona c. 431/2002 Z. z.
 * o uctovnictve a z Peppol BIS Billing 3.0.
 */
public final class InvoiceValidator {
    private static final Set<BigDecimal> SK_VAT_RATES = Set.of(
            BigDecimal.valueOf(23), BigDecimal.valueOf(19), BigDecimal.valueOf(5));

    private InvoiceValidator() {
    }

    public static List<String> validate(Invoice inv) {
        List<String> errors = new ArrayList<>();

        if (isBlank(inv.number())) {
            errors.add("Chýba poradové číslo faktúry.");
        }
        if (inv.issueDate() == null) {
            errors.add("Chýba dátum vyhotovenia.");
        }
        if (inv.deliveryDate() == null) {
            errors.add("Chýba dátum dodania.");
        }
        if (inv.dueDate() != null && inv.issueDate() != null && inv.dueDate().isBefore(inv.issueDate())) {
            errors.add("Dátum splatnosti je pred dátumom vyhotovenia.");
        }

        validateParty("Dodávateľ", inv.seller(), errors);
        validateParty("Odberateľ", inv.buyer(), errors);

        if (inv.lines().isEmpty()) {
            errors.add("Faktúra nemá žiadne položky.");
        }

        boolean sellerIsVatPayer = inv.seller().isVatRegistered();
        for (int i = 0; i < inv.lines().size(); i++) {
            validateLine(i + 1, inv.lines().get(i), sellerIsVatPayer, inv.buyer(), errors);
        }

        if (!isBlank(inv.variableSymbol()) && !Identifiers.isVariableSymbol(inv.variableSymbol())) {
            errors.add("Variabilný symbol môže mať najviac 10 číslic.");
        }
        if (!isBlank(inv.payeeIban()) && !Identifiers.isIban(inv.payeeIban())) {
            errors.add("IBAN nie je platný.");
        }
        if (inv.totals().payableAmount().signum() > 0 && inv.dueDate() == null) {
            errors.add("Chýba dátum splatnosti.");
        }
        return errors;
    }

    /** Dodatocne podmienky na odoslanie cez Peppol (digitalny postar). */
    public static List<String> validateForPeppol(Invoice inv) {
        List<String> errors = validate(inv);
        if (inv.seller().peppolId() == null) {
            errors.add("Dodávateľ nemá DIČ, preto nemá Peppol ID (0245:DIČ).");
        }
        if (inv.buyer().peppolId() == null) {
            errors.add("Odberateľ nemá DIČ - e-faktúru mu nie je kam doručiť, použite PDF.");
        }
        if (isBlank(inv.seller().city()) || isBlank(inv.buyer().city())) {
            errors.add("Peppol vyžaduje adresu dodávateľa aj odberateľa.");
        }
        return errors;
    }

    private static void validateParty(String label, Party p, List<String> errors) {
        if (isBlank(p.name())) {
            errors.add(label + ": chýba názov.");
        }
        if (!isBlank(p.ico()) && !Identifiers.isIco(p.ico())) {
            errors.add(label + ": IČO musí mať 8 číslic.");
        }
        if (!isBlank(p.dic()) && "SK".equals(p.country()) && !Identifiers.isDic(p.dic())) {
            errors.add(label + ": DIČ musí mať 10 číslic.");
        }
        if (!isBlank(p.icDph()) && "SK".equals(p.country()) && !Identifiers.isSlovakIcDph(p.icDph())) {
            errors.add(label + ": IČ DPH musí byť v tvare SK + 10 číslic.");
        }
    }

    private static void validateLine(int no, InvoiceLine line, boolean sellerIsVatPayer, Party buyer,
                                     List<String> errors) {
        String prefix = "Položka " + no + ": ";
        if (isBlank(line.description())) {
            errors.add(prefix + "chýba popis.");
        }
        if (line.quantity().signum() == 0) {
            errors.add(prefix + "množstvo nemôže byť 0.");
        }
        if (line.unitPrice().signum() < 0) {
            errors.add(prefix + "jednotková cena nemôže byť záporná (zľavu zadajte záporným množstvom).");
        }
        if (!sellerIsVatPayer && line.vatCategory() != VatCategory.NOT_SUBJECT) {
            errors.add(prefix + "dodávateľ nie je platiteľ DPH, položka nesmie mať DPH.");
        }
        if (sellerIsVatPayer && line.vatCategory() == VatCategory.NOT_SUBJECT) {
            errors.add(prefix + "platiteľ DPH musí uviesť kategóriu a sadzbu DPH.");
        }
        if (line.vatCategory() == VatCategory.STANDARD
                && !SK_VAT_RATES.contains(line.vatRate().stripTrailingZeros())) {
            errors.add(prefix + "sadzba DPH musí byť 23, 19 alebo 5 %.");
        }
        if (line.vatCategory() != VatCategory.STANDARD && line.vatRate() != null && line.vatRate().signum() != 0) {
            errors.add(prefix + "kategória " + line.vatCategory().code() + " musí mať sadzbu 0 %.");
        }
        if (line.vatCategory() == VatCategory.REVERSE_CHARGE && !buyer.isVatRegistered()) {
            errors.add(prefix + "prenesenie daňovej povinnosti vyžaduje IČ DPH odberateľa.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
