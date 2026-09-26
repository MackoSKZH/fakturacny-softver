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

    /** Prilohy partnerov su vzdy len pre editorov - rovnako ako cela karta partnera. */
    public boolean visibleTo(boolean editor) {
        return editor || (!this.editorsOnly && this.partnerId == null);
    }
}
