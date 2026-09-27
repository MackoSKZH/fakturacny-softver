package sk.firstglobal.hq.web.access;

import sk.firstglobal.hq.web.security.AppSecurityProperties;
import sk.firstglobal.hq.web.security.CurrentUser;
import sk.firstglobal.hq.web.security.SecurityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * /pozvanka/{token} - verejna stranka pozvanky. Neprihlaseneho si token zapamata v relacii, posle na prihlasenie
 * (Google pusti dnu aj e-mail, ktory este nie je v zozname, ak ma platnu pozvanku) a po prihlaseni ho vrati sem.
 */
@Controller
class InviteController {
    private final AccessService service;
    private final AccessRepository repo;
    private final AppSecurityProperties props;
    private final Clock clock;

    InviteController(AccessService service, AccessRepository repo, AppSecurityProperties props, Clock clock) {
        this.service = service;
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/pozvanka/{token:[A-Za-z0-9_-]{43}}")
    String page(@PathVariable String token, HttpServletRequest request, Model model) {
        AccessRepository.Invite i = this.service.invite(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(this.clock);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean loggedIn = auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
        if (i.isUsable(now)) {
            request.getSession(true).setAttribute(SecurityConfig.PENDING_INVITE, token);
        } else if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute(SecurityConfig.PENDING_INVITE);
        }
        model.addAttribute("i", i);
        model.addAttribute("usable", i.isUsable(now));
        model.addAttribute("state", i.state(now));
        model.addAttribute("roleNames", i.roleIds().stream()
                .map(r -> this.repo.role(r).map(AccessRepository.Role::name).orElse("?")).toList());
        model.addAttribute("maskedEmail", i.email() == null ? null : AccessService.mask(i.email()));
        model.addAttribute("loggedIn", loggedIn);
        model.addAttribute("me", loggedIn ? CurrentUser.name(auth) : null);
        model.addAttribute("loginUrl", this.props.mode() == AppSecurityProperties.Mode.GOOGLE
                ? "/oauth2/authorization/google" : "/login");
        model.addAttribute("token", token);
        return "admin/invite";
    }

    @PostMapping("/pozvanka/{token:[A-Za-z0-9_-]{43}}/prijat")
    String accept(@PathVariable String token, Authentication auth, HttpSession session, RedirectAttributes redirect) {
        String name = auth.getPrincipal() instanceof OidcUser o ? o.getFullName() : null;
        try {
            AccessService.Accepted done = this.service.accept(token, CurrentUser.name(auth), name);
            session.removeAttribute(SecurityConfig.PENDING_INVITE);
            redirect.addFlashAttribute("message", done.message());
            // rovno na cielovu stranku - pri dvoch presmerovaniach by sa privitanie stratilo
            return this.service.resolve(CurrentUser.name(auth), name).access().isActivitiesRead() ? "redirect:/aktivity"
                    : "redirect:/moj-program";
        } catch (AccessException e) {
            redirect.addFlashAttribute("errors", e.errors());
            return "redirect:/pozvanka/" + token;
        }
    }
}
