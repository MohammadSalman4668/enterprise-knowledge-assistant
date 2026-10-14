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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless HTTP Basic security.
 * <ul>
 *   <li>CSRF protection is disabled because no cookie or session is used: the SPA sends an
 *       explicit Authorization header from JavaScript, which a cross-site form cannot forge.</li>
 *   <li>Coarse rules (URL + role) live here. Fine-grained rules (document access level,
 *       ownership, lifecycle transitions) live in the services and are enforced there too.</li>
 *   <li>401/403 are JSON without {@code WWW-Authenticate}, so the browser never shows its
 *       native login pop-up; the SPA has its own sign-in form.</li>
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

    private AuthenticationEntryPoint jsonEntryPoint() {
        return (request, response, ex) -> writeJson(response, 401, "UNAUTHENTICATED",
                "Valid credentials are required", request.getRequestURI());
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
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"path\":\"" + safePath
                + "\",\"fieldErrors\":[]}");
    }
}
