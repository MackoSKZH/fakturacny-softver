package com.fakturacnysoftver.web;

import com.fakturacnysoftver.web.attachment.AttachmentCategory;
import com.fakturacnysoftver.web.security.CurrentUser;
import com.fakturacnysoftver.web.security.SecurityConfig;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Meno prihlaseneho pouzivatela do hlavicky kazdej stranky. */
@ControllerAdvice
class CurrentUserAdvice {

    /** Ci prihlaseny smie zapisovat - sablony podla toho skryvaju akcie. */
    @ModelAttribute("canEdit")
    boolean canEdit(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + SecurityConfig.EDITOR).equals(a.getAuthority()));
    }

    /** Druhy podkladov pre formular nahravania (Thymeleaf 3.1 nepovoluje T() na nase triedy). */
    @ModelAttribute("attachmentCategories")
    AttachmentCategory[] attachmentCategories() {
        return AttachmentCategory.values();
    }

    @ModelAttribute("currentUser")
    String currentUser(Authentication auth) {
        return auth == null ? null : CurrentUser.name(auth);
    }
}
