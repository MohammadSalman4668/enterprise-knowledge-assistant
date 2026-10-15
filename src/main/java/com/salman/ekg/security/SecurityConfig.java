package com.salman.ekg.security;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.salman.ekg.config.WebPageRoutes;

/**
 * Stateless HTTP Basic security.
 * <ul>
 *   <li>CSRF protection is disabled because no cookie or session is used: the SPA sends an
 *       explicit Authorization header from JavaScript, which a cross-site form cannot forge.</li>
 *   <li>Coarse rules (URL + role) live here. Fine-grained rules (document access level,
 *       ownership, lifecycle transitions) live in the services and are enforced there too.</li>
 *   <li>401/403 are JSON without {@code WWW-Authenticate}, so the browser never shows its
 *       native login pop-up; the SPA has its own sign-in form.</li>
 *   <li>Users sign in with email + password. A PENDING or REJECTED account is reported as such
 *       only <em>after</em> the password is verified, so the sign-in form cannot be used to
 *       discover which emails have pending requests.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/favicon.svg", "/error").permitAll()
                        // Page URLs only return the app shell (no data); data stays behind /api/**.
                        .requestMatchers(WebPageRoutes.PAGE_PATHS.toArray(String[]::new)).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup").permitAll()
                        .requestMatchers("/api/users/**").hasAnyRole("HR", "ADMIN")
                        .requestMatchers("/api/knowledge/analytics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/knowledge/documents/*/reindex").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/knowledge/documents/*/approve",
                                "/api/knowledge/documents/*/reject",
                                "/api/knowledge/documents/*/archive").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/knowledge/documents",
                                "/api/knowledge/documents/*/revisions").hasAnyRole("HR", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(jsonEntryPoint()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonEntryPoint())
                        .accessDeniedHandler(jsonAccessDenied()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Password first, account status second (the default checks status before the password). */
    @Bean
    AuthenticationProvider authenticationProvider(DatabaseUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isAccountNonLocked()) {
                throw new LockedException("Your sign-up request was rejected. Contact an administrator.");
            }
            if (!user.isEnabled()) {
                boolean employee = user.getAuthorities().stream().anyMatch(a -> "ROLE_EMPLOYEE".equals(a.getAuthority()));
                throw new DisabledException("Your account is awaiting approval by " + (employee ? "HR" : "an Admin") + ".");
            }
        });
        return provider;
    }

    private AuthenticationEntryPoint jsonEntryPoint() {
        return (request, response, ex) -> {
            if (ex instanceof DisabledException) {
                writeJson(response, 401, "ACCOUNT_PENDING", ex.getMessage(), request.getRequestURI());
            } else if (ex instanceof LockedException) {
                writeJson(response, 401, "ACCOUNT_REJECTED", ex.getMessage(), request.getRequestURI());
            } else if (ex instanceof BadCredentialsException || ex instanceof UsernameNotFoundException) {
                writeJson(response, 401, "UNAUTHENTICATED", "Incorrect email or password", request.getRequestURI());
            } else {
                writeJson(response, 401, "UNAUTHENTICATED", "Valid credentials are required", request.getRequestURI());
            }
        };
    }

    private AccessDeniedHandler jsonAccessDenied() {
        return (request, response, ex) -> writeJson(response, 403, "FORBIDDEN",
                "You are not allowed to perform this action", request.getRequestURI());
    }

    private static void writeJson(HttpServletResponse response, int status, String code,
                                  String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String safePath = path == null ? "" : path.replaceAll("[\"\\\\]", "");
        String safeMessage = message == null ? "" : message.replaceAll("[\"\\\\]", "");
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"message\":\"" + safeMessage + "\",\"path\":\"" + safePath
                + "\",\"fieldErrors\":[]}");
    }
}
