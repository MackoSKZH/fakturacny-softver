package com.fakturacnysoftver.web.security;

import com.fakturacnysoftver.web.access.AccessFilter;
import com.fakturacnysoftver.web.access.AccessInfo;
import com.fakturacnysoftver.web.access.AccessService;
import com.fakturacnysoftver.web.access.Permission;

import jakarta.servlet.http.HttpSession;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.function.BiPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfig {

    /** Relacny atribut: token pozvanky, ktoru clovek otvoril pred prihlasenim. */
    public static final String PENDING_INVITE = "fgs.pendingInvite";

    private static String perm(Permission p) {
        return p.authority();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppSecurityProperties props, AccessService access)
            throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/actuator/health", "/error").permitAll()
                // Bez prihlasenia: odber kalendara (tajny token), verejna podpora aktivity, stranka pozvanky.
                .requestMatchers(HttpMethod.GET, "/kalendar/*", "/podpora/*", "/pozvanka/*").permitAll()
                // Kazdy prihlaseny: svoj program, dochadzka a potvrdenia; prijatie pozvanky; prilohy (kontroluje controller).
                .requestMatchers("/", "/moj-program", "/moj-program/**", "/moja-dochadzka", "/moja-dochadzka/**",
                        "/pozvanka/*/prijat", "/prilohy/**").authenticated()
                .requestMatchers("/sprava", "/sprava/**", "/nastavenia", "/nastavenia/**").hasAuthority(perm(Permission.ADMIN))
                .requestMatchers("/exporty", "/exporty/**").hasAuthority(perm(Permission.EXPORTS))
                // Adresar ludi obsahuje kontakty na maloletych a ich zastupcov (minimalizacia GDPR).
                .requestMatchers("/ludia", "/ludia/**").hasAuthority(perm(Permission.PEOPLE))
                .requestMatchers("/partneri", "/partneri/**").hasAuthority(perm(Permission.PARTNERS))
                .requestMatchers("/dochadzka", "/dochadzka/**").hasAuthority(perm(Permission.TIMESHEETS))
                // Financie: citanie a zapis zvlast.
                .requestMatchers("/faktury/nova", "/faktury/*/dobropis", "/polozky/banka", "/polozky/banka/**").hasAuthority(perm(Permission.FINANCE_WRITE))
                .requestMatchers(HttpMethod.GET, "/polozky", "/polozky/**", "/api/polozky", "/api/polozky/**",
                        "/faktury", "/faktury/**", "/odberatelia", "/odberatelia/**", "/projekty", "/projekty/**",
                        "/financovanie", "/financovanie/**").hasAuthority(perm(Permission.FINANCE_READ))
                .requestMatchers("/polozky", "/polozky/**", "/api/polozky", "/api/polozky/**", "/faktury", "/faktury/**",
                        "/odberatelia", "/odberatelia/**", "/projekty", "/projekty/**", "/financovanie",
                        "/financovanie/**").hasAuthority(perm(Permission.FINANCE_WRITE))
                .requestMatchers(HttpMethod.GET, "/majetok", "/majetok/**")
                        .hasAnyAuthority(perm(Permission.ACTIVITIES_READ), perm(Permission.ASSETS))
                .requestMatchers("/majetok", "/majetok/**").hasAuthority(perm(Permission.ASSETS))
                // Aktivity: rozpis s e-mailmi a potvrdenia - adresar ludi alebo vlastnik aktivity.
                .requestMatchers("/aktivity/{id}/rozpis.*", "/aktivity/{id}/potvrdenia.zip")
                        .access(activity(AccessInfo::canSeeTeamContacts))
                .requestMatchers(HttpMethod.GET, "/aktivity").hasAuthority(perm(Permission.ACTIVITIES_READ))
                .requestMatchers(HttpMethod.GET, "/aktivity/{id}", "/aktivity/{id}/**")
                        .access(activity((a, id) -> a.isActivitiesRead() || a.owns(id)))
                .requestMatchers(HttpMethod.POST, "/aktivity").hasAuthority(perm(Permission.ACTIVITIES_WRITE))
                // Zmena konkretnej aktivity: zapis na vsetky aktivity, alebo vlastnik (projektovy manazer).
                .requestMatchers("/aktivity/{id}", "/aktivity/{id}/**").access(activity(AccessInfo::canEditActivity))
                .anyRequest().authenticated());

        if (props.mode() == AppSecurityProperties.Mode.GOOGLE) {
            http.oauth2Login(o -> o
                    .loginPage("/login")
                    .userInfoEndpoint(u -> u.oidcUserService(allowlistedOidcUsers(access))));
        } else {
            http.formLogin(f -> f.loginPage("/login").permitAll());
        }
        http.addFilterBefore(new AccessFilter(access), AuthorizationFilter.class);
        http.logout(l -> l.logoutSuccessUrl("/login?logout"));
        http.headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'")));
        return http.build();
    }

    /** Pravidlo nad aktivitou z URL (/aktivity/{id}/...) - vlastnictvo sa overuje podla ID v adrese. */
    private static AuthorizationManager<RequestAuthorizationContext> activity(BiPredicate<AccessInfo, Long> rule) {
        return (authentication, ctx) -> {
            String id = ctx.getVariables().get("id");
            boolean ok = id != null && id.matches("\\d{1,18}")
                    && rule.test(AccessFilter.of(ctx.getRequest()), Long.parseLong(id));
            return new AuthorizationDecision(ok);
        };
    }

    /**
     * Lokalny rezim je na skusanie (demo): jedno spolocne heslo pre lokalneho admina, citatelov z konfiguracie,
     * pouzivatelov zalozenych v aplikacii a toho, kto prave prijima pozvanku. Produkcia pouziva Google prihlasenie.
     */
    @Bean
    UserDetailsService localUsers(AppSecurityProperties props, PasswordEncoder encoder, AccessService access) {
        if (props.mode() != AppSecurityProperties.Mode.LOCAL) {
            return new InMemoryUserDetailsManager();
        }
        String password = encoder.encode(props.localPassword());
        return username -> {
            String u = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
            if (!access.mayLogIn(u) && !access.inviteAllows(pendingInvite(), u)) {
                throw new UsernameNotFoundException(u);
            }
            return User.withUsername(u).password(password).roles("USER").build();
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Google overi identitu, my overime, ci ma e-mail pristup (alebo prave prijima platnu pozvanku). */
    static OAuth2UserService<OidcUserRequest, OidcUser> allowlistedOidcUsers(AccessService access) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            String email = user.getEmail();
            if (!Boolean.TRUE.equals(user.getEmailVerified())
                    || (!access.mayLogIn(email) && !access.inviteAllows(pendingInvite(), email))) {
                throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"),
                        "Účet " + email + " nemá prístup. Požiadajte admina o pozvánku.");
            }
            List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "email");
        };
    }

    /** Token pozvanky z relacie - ulozi ho stranka /pozvanka/{token} pred presmerovanim na prihlasenie. */
    static String pendingInvite() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpSession session = attrs.getRequest().getSession(false);
            Object t = session == null ? null : session.getAttribute(PENDING_INVITE);
            return t instanceof String s ? s : null;
        }
        return null;
    }
}
