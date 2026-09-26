package sk.firstglobal.hq.web;

import sk.firstglobal.hq.web.access.AccessFilter;
import sk.firstglobal.hq.web.access.AccessInfo;
import sk.firstglobal.hq.web.attachment.AttachmentCategory;

import jakarta.servlet.http.HttpServletRequest;
import sk.firstglobal.hq.web.security.CurrentUser;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Meno prihlaseneho pouzivatela do hlavicky kazdej stranky. */
@ControllerAdvice
class CurrentUserAdvice {

    /** Co smie prihlaseny - sablony podla toho zobrazuju menu a akcie (${acc.financeWrite}, ...). */
    @ModelAttribute("acc")
    AccessInfo access(HttpServletRequest request) {
        return AccessFilter.of(request);
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
