package com.fakturacnysoftver.web.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
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

    public static final String EDITOR = "EDITOR";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppSecurityProperties props) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/actuator/health", "/error").permitAll()
                // Odber kalendara z Google/Outlook/iPhone - bez prihlasenia, chraneny 256-bitovym tokenom v URL.
                .requestMatchers(HttpMethod.GET, "/kalendar/*").permitAll()
                // Zapis (a formulare, ktore k nemu vedu) len pre editorov; clenovia citaju.
                // Platený clovek zapisuje vlastne hodiny aj bez roly editora - vlastnictvo overuje controller.
                .requestMatchers("/moja-dochadzka", "/moja-dochadzka/**").authenticated()
                .requestMatchers("/moj-program", "/moj-program/**").authenticated()
                // Hromadne exporty a rozpis s e-mailami obsahuju osobne udaje.
                .requestMatchers("/exporty", "/exporty/**", "/aktivity/*/rozpis.*").hasRole(EDITOR)
                .requestMatchers("/dochadzka", "/dochadzka/**").hasRole(EDITOR)
                .requestMatchers(HttpMethod.POST, "/**").hasRole(EDITOR)
                .requestMatchers(HttpMethod.PUT, "/**").hasRole(EDITOR)
                .requestMatchers(HttpMethod.DELETE, "/**").hasRole(EDITOR)
                .requestMatchers("/faktury/nova", "/faktury/*/dobropis").hasRole(EDITOR)
                // Adresar ludi obsahuje kontakty na maloletych a ich zastupcov - len editori (minimalizacia GDPR).
                .requestMatchers("/ludia", "/ludia/**").hasRole(EDITOR)
                .anyRequest().authenticated());

        if (props.mode() == AppSecurityProperties.Mode.GOOGLE) {
            http.oauth2Login(o -> o
                    .loginPage("/login")
                    .userInfoEndpoint(u -> u.oidcUserService(allowlistedOidcUsers(props))));
        } else {
            http.formLogin(f -> f.loginPage("/login").permitAll());
        }
        http.logout(l -> l.logoutSuccessUrl("/login?logout"));
        http.headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'")));
        return http.build();
    }

    @Bean
    UserDetailsService localUsers(AppSecurityProperties props, PasswordEncoder encoder) {
        if (props.mode() != AppSecurityProperties.Mode.LOCAL) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(props.localUsername())
                .password(encoder.encode(props.localPassword()))
                .roles("USER", EDITOR)
                .build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Google overi identitu, my overime, ci je dany e-mail clenom zdruzenia s pristupom. */
    static OAuth2UserService<OidcUserRequest, OidcUser> allowlistedOidcUsers(AppSecurityProperties props) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            if (!Boolean.TRUE.equals(user.getEmailVerified()) || !props.isAllowed(user.getEmail())) {
                throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"),
                        "Účet " + user.getEmail() + " nemá prístup.");
            }
            List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            if (props.isEditor(user.getEmail())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + EDITOR));
            }
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "email");
        };
    }
}
