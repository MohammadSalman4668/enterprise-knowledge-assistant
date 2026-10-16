package com.salman.ekg.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared infrastructure beans: an injectable clock (so tests control time) and the executor that runs
 * retriever and model calls under a time budget.
 */
@Configuration
public class AppConfig {

    /** Injected everywhere "now" is needed, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs retriever and model calls so they can be abandoned on timeout without blocking the request thread forever. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService knowledgeExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
