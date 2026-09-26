package com.fakturacnysoftver.web.attachment;

import java.time.OffsetDateTime;

public record Attachment(
        long id,
        Long projectId,
        Long partnerId,
        Long dealId,
        String category,
        String fileName,
        String contentType,
        int sizeBytes,
        boolean editorsOnly,
        String note,
        String uploadedBy,
        OffsetDateTime uploadedAt) {

    public String categoryLabel() {
        return AttachmentCategory.labelOf(this.category);
    }

    public String sizeLabel() {
        return this.sizeBytes >= 1024 * 1024 ? String.format("%.1f MB", this.sizeBytes / 1048576.0).replace('.', ',')
                : Math.max(1, this.sizeBytes / 1024) + " kB";
    }

    /** Kam sa vratit po zmazani - stranka veci, ku ktorej subor patri. */
    public String ownerUrl() {
        if (this.projectId != null) {
            return "/aktivity/" + this.projectId;
        }
        return this.partnerId != null ? "/partneri/" + this.partnerId : "/financovanie/" + this.dealId;
    }

    /**
     * Kto subor vidi: podklady aktivity kazdy, kto vidi aktivitu (subory "len pre vedenie" len ten, kto ju smie
     * upravovat, alebo financie); dohody financie; partneri len opravnenie na partnerov.
     */
    public boolean visibleTo(com.fakturacnysoftver.web.access.AccessInfo a) {
        if (this.projectId != null) {
            return this.editorsOnly ? a.canEditActivity(this.projectId) || a.isFinanceRead()
                    : a.isActivitiesRead() || a.owns(this.projectId);
        }
        return this.dealId != null ? a.isFinanceRead() && (!this.editorsOnly || a.isFinanceWrite()) : a.isPartners();
    }

    /** Kto smie subor zmazat - ten, kto smie upravovat vec, ku ktorej patri. */
    public boolean deletableBy(com.fakturacnysoftver.web.access.AccessInfo a) {
        if (this.projectId != null) {
            return a.canEditActivity(this.projectId);
        }
        return this.dealId != null ? a.isFinanceWrite() : a.isPartners();
    }
}
