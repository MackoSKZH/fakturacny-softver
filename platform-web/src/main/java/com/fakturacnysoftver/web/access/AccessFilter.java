package com.fakturacnysoftver.web.access;

import com.fakturacnysoftver.web.security.CurrentUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pri kazdej poziadavke nacita opravnenia prihlaseneho z databazy a nahradi nimi tie z relacie. Odobrata rola
 * alebo deaktivacia tak plati hned, nie az po odhlaseni. Nie je to @Component - registruje ho len SecurityConfig,
 * aby nebezal dvakrat.
 */
public class AccessFilter extends OncePerRequestFilter {
    public static final String ATTR = "fgs.access";

    private final AccessService access;

    public AccessFilter(AccessService access) {
        this.access = access;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI().substring(request.getContextPath().length());
        return p.startsWith("/css/") || p.startsWith("/js/") || p.equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            request.setAttribute(ATTR, AccessInfo.NONE);
            chain.doFilter(request, response);
            return;
        }
        String name = auth.getPrincipal() instanceof OidcUser oidc ? oidc.getFullName() : null;
        AccessService.Resolution r = this.access.resolve(CurrentUser.name(auth), name);
        if (r.disabled()) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendRedirect(request.getContextPath() + "/login?disabled");
            return;
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        r.access().permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.authority())));
        Authentication refreshed = auth instanceof OAuth2AuthenticationToken o
                ? new OAuth2AuthenticationToken(o.getPrincipal(), authorities, o.getAuthorizedClientRegistrationId())
                : UsernamePasswordAuthenticationToken.authenticated(auth.getPrincipal(), null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(refreshed);
        SecurityContextHolder.setContext(context);
        request.setAttribute(ATTR, r.access());
        chain.doFilter(request, response);
    }

    public static AccessInfo of(HttpServletRequest request) {
        Object a = request.getAttribute(ATTR);
        return a instanceof AccessInfo info ? info : AccessInfo.NONE;
    }
}
