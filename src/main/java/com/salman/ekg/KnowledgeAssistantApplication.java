package com.salman.ekg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeAssistantApplication.class, args);
    }
}
