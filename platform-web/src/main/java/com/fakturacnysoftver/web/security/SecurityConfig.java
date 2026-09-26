package com.fakturacnysoftver.web.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppSecurityProperties props) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/actuator/health", "/error").permitAll()
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
                .roles("USER")
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
            return user;
        };
    }
}
