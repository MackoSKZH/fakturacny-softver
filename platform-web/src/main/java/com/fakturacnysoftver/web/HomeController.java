package com.fakturacnysoftver.web;

import com.fakturacnysoftver.web.security.AppSecurityProperties;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HomeController {
    private final AppSecurityProperties security;

    HomeController(AppSecurityProperties security) {
        this.security = security;
    }

    /** Dobrovolnik bez pristupu k aktivitam zacina svojim programom. */
    @GetMapping("/")
    String home(jakarta.servlet.http.HttpServletRequest request) {
        return com.fakturacnysoftver.web.access.AccessFilter.of(request).isActivitiesRead() ? "redirect:/aktivity"
                : "redirect:/moj-program";
    }

    @GetMapping("/login")
    String login(Model model, @org.springframework.web.bind.annotation.RequestParam(required = false) String disabled) {
        model.addAttribute("googleLogin", this.security.mode() == AppSecurityProperties.Mode.GOOGLE);
        model.addAttribute("disabled", disabled != null);
        return "login";
    }
}
