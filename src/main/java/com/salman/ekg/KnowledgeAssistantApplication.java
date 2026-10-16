package com.salman.ekg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Enterprise Knowledge Assistant. {@code @ConfigurationPropertiesScan} binds the
 * {@code ekg.*} settings ({@link com.salman.ekg.config.KnowledgeProperties}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeAssistantApplication.class, args);
    }
}
