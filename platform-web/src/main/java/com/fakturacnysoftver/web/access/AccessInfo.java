package com.fakturacnysoftver.web.access;

import java.util.Set;

/**
 * Co smie prihlaseny pouzivatel - pocita sa z databazy pri kazdej poziadavke, takze odobratie roly
 * alebo deaktivacia platia okamzite. Sablony ho maju ako ${acc}.
 */
public record AccessInfo(Long userId, String email, Set<Permission> permissions, Set<Long> ownedProjects,
                         boolean bootstrapAdmin) {

    public static final AccessInfo NONE = new AccessInfo(null, null, Set.of(), Set.of(), false);

    public boolean has(Permission p) {
        return this.permissions.contains(p);
    }

    public boolean isAdmin() {
        return this.has(Permission.ADMIN);
    }

    public boolean isActivitiesRead() {
        return this.has(Permission.ACTIVITIES_READ);
    }

    public boolean isActivitiesWrite() {
        return this.has(Permission.ACTIVITIES_WRITE);
    }

    public boolean isPeople() {
        return this.has(Permission.PEOPLE);
    }

    public boolean isPartners() {
        return this.has(Permission.PARTNERS);
    }

    public boolean isFinanceRead() {
        return this.has(Permission.FINANCE_READ);
    }

    public boolean isFinanceWrite() {
        return this.has(Permission.FINANCE_WRITE);
    }

    public boolean isTimesheets() {
        return this.has(Permission.TIMESHEETS);
    }

    public boolean isAssets() {
        return this.has(Permission.ASSETS);
    }

    public boolean isExports() {
        return this.has(Permission.EXPORTS);
    }

    public boolean owns(long projectId) {
        return this.ownedProjects.contains(projectId);
    }

    /** Upravovat aktivitu smie ten, kto ma zapis na vsetky aktivity, alebo jej vlastnik. */
    public boolean canEditActivity(long projectId) {
        return this.isActivitiesWrite() || this.owns(projectId);
    }

    /** Rozpocet a cerpanie aktivity: financie alebo jej vlastnik. */
    public boolean canSeeActivityBudget(long projectId) {
        return this.isFinanceRead() || this.owns(projectId);
    }

    /** Rozpis s e-mailmi a potvrdenia pre tim: adresar ludi alebo vlastnik aktivity. */
    public boolean canSeeTeamContacts(long projectId) {
        return this.isPeople() || this.owns(projectId);
    }
}
