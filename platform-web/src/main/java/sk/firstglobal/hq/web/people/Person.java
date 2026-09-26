package sk.firstglobal.hq.web.people;

import java.time.LocalDate;
import java.util.List;

public record Person(
        Long id,
        String fullName,
        String email,
        String phone,
        String organization,
        List<String> roles,
        boolean minor,
        String guardianName,
        String guardianContact,
        LocalDate dataConsentOn,
        boolean consentByGuardian,
        boolean photoConsent,
        String note) {

    /** Co chyba podla GDPR / zakona 18/2018 a zakona 406/2011 - null ak je vsetko v poriadku. */
    public String consentIssue() {
        if (this.minor && (this.guardianName == null || this.guardianName.isBlank())) {
            return "Maloletý bez zákonného zástupcu";
        }
        if (this.minor && !this.consentByGuardian) {
            return "Chýba súhlas zákonného zástupcu";
        }
        if (this.dataConsentOn == null) {
            return "Chýba súhlas so spracovaním údajov";
        }
        return null;
    }

    public List<String> roleLabels() {
        return this.roles.stream().map(PersonRole::labelOf).toList();
    }
}
