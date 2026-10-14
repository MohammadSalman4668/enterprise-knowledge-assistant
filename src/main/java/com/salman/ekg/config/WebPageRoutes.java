package com.salman.ekg.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The frontend uses clean URLs (/ask, /login, /documents/12, …) handled in the browser by
 * the History API. When such a URL is opened directly or refreshed, the server must return
 * the single-page app shell (index.html); the JavaScript router then renders the screen.
 * The shell contains no data; everything else is fetched from /api/**, which requires sign-in.
 */
@Configuration
public class WebPageRoutes implements WebMvcConfigurer {

    /** Page paths served by the single-page app. Kept in sync with the routes in js/app.js. */
    public static final List<String> PAGE_PATHS = List.of(
            "/login", "/ask", "/search", "/documents", "/documents/*", "/source/*", "/analytics");

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        PAGE_PATHS.forEach(path -> registry.addViewController(path).setViewName("forward:/index.html"));
    }
}
