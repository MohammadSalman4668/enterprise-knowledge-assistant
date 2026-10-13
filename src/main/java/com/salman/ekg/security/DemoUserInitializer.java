package com.salman.ekg.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.repository.AppUserRepository;

/**
 * Sets the password of the synthetic demo users at startup so that no password is
 * ever committed. Uses EKG_DEMO_PASSWORD when provided; otherwise generates a random
 * one and logs it once (the same approach Spring Boot uses for its default user).
 */
@Component
@Order(1)
public class DemoUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserInitializer.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final KnowledgeProperties props;

    public DemoUserInitializer(AppUserRepository users, PasswordEncoder encoder, KnowledgeProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = props.demo() == null ? null : props.demo().password();
        if (password == null || password.isBlank()) {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("EKG_DEMO_PASSWORD is not set. Generated demo password for employee/engineer/hr/admin: {}", password);
        }
        String hash = encoder.encode(password);
        users.findAll().forEach(u -> u.setPasswordHash(hash));
    }
}
