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

    @GetMapping("/")
    String home() {
        return "redirect:/faktury";
    }

    @GetMapping("/login")
    String login(Model model) {
        model.addAttribute("googleLogin", this.security.mode() == AppSecurityProperties.Mode.GOOGLE);
        return "login";
    }
}
