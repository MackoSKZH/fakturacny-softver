package sk.firstglobal.hq.web.partner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Dohoda o financovani so suhrnom prepojenych poloziek a protiplneni. */
public record Deal(
        long id,
        long partnerId,
        String partnerName,
        Long projectId,
        String projectCode,
        String title,
        String kind,
        String stage,
        BigDecimal amount,
        LocalDate expectedOn,
        String nextStep,
        LocalDate nextStepOn,
        String program,
        LocalDate appliedOn,
        LocalDate periodFrom,
        LocalDate periodTo,
        LocalDate reportDueOn,
        LocalDate reportedOn,
        String note,
        BigDecimal received,
        BigDecimal spent,
        int outOfPeriod,
        int deliverablesTotal,
        int deliverablesDone,
        int deliverablesOverdue,
        String paymentVs,
        String customVs) {

    /** Predvoleny VS dohody je VS_BASE + id; 800000+ su zbierky aktivit. */
    public static final long VS_BASE = 700000;

    public String kindLabel() {
        return DealKind.labelOf(this.kind);
    }

    public String stageLabel() {
        DealStage s = DealStage.parse(this.stage);
        return s == null ? this.stage : s.label();
    }

    public boolean isGrant() {
        return DealKind.GRANT.name().equals(this.kind);
    }

    public BigDecimal remainingToSpend() {
        return this.amount.subtract(this.spent);
    }

    /** Veci, na ktore treba upozornit - radsej teraz nez pri kontrole z nadacie alebo danoveho uradu. */
    public List<String> warnings(LocalDate today) {
        List<String> w = new ArrayList<>();
        DealStage s = DealStage.parse(this.stage);
        if (DealKind.DAR.name().equals(this.kind) && this.deliverablesTotal > 0) {
            w.add("Dar s protiplnením: ak protiplnenie propaguje darcu (logo, reklama), daňovo ide o reklamu, nie o dar."
                    + " Overte s účtovníkom a zvážte faktúru.");
        }
        if (s == DealStage.DOHODNUTE && this.expectedOn != null && this.expectedOn.isBefore(today)
                && this.received.compareTo(this.amount) < 0) {
            w.add("Platba mala prísť do " + fmt(this.expectedOn) + ", prijaté " + money(this.received) + " z "
                    + money(this.amount) + " €.");
        }
        if (s != null && s != DealStage.ZAPLATENE && s != DealStage.ODMIETNUTE && this.nextStepOn != null
                && this.nextStepOn.isBefore(today)) {
            w.add("Ďalší krok po termíne (" + fmt(this.nextStepOn) + ")" + (this.nextStep == null ? "" : ": " + this.nextStep));
        }
        if (this.deliverablesOverdue > 0) {
            w.add(this.deliverablesOverdue + " protiplnenie(a) po termíne - partner to zbadá skôr než my.");
        }
        if (this.isGrant()) {
            if (this.spent.compareTo(this.amount) > 0 && this.amount.signum() > 0) {
                w.add("Čerpanie prekračuje grant o " + money(this.spent.subtract(this.amount)) + " € - rozdiel ide z vlastných zdrojov.");
            }
            if (this.outOfPeriod > 0) {
                w.add(this.outOfPeriod + " výdavok(ky) mimo oprávneného obdobia - grantor ich pravdepodobne neuzná.");
            }
            if (this.reportedOn == null && this.reportDueOn != null && s != DealStage.ODMIETNUTE) {
                if (this.reportDueOn.isBefore(today)) {
                    w.add("Termín vyúčtovania " + fmt(this.reportDueOn) + " uplynul a vyúčtovanie nie je odovzdané!");
                } else if (!this.reportDueOn.isAfter(today.plusDays(30))) {
                    w.add("Vyúčtovanie treba odovzdať do " + fmt(this.reportDueOn) + ".");
                }
            }
        }
        return w;
    }

    static String fmt(LocalDate d) {
        return d.getDayOfMonth() + ". " + d.getMonthValue() + ". " + d.getYear();
    }

    static String money(BigDecimal b) {
        return sk.firstglobal.hq.web.export.Table.text(b);
    }
}
