package com.fakturacnysoftver.web;

import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Meno prihlaseneho pouzivatela do hlavicky kazdej stranky. */
@ControllerAdvice
class CurrentUserAdvice {

    @ModelAttribute("currentUser")
    String currentUser(Authentication auth) {
        return auth == null ? null : CurrentUser.name(auth);
    }
}
