package com.fleetcopilot.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the browser UI (served from another port) call the fleet API directly. Local-only project
 * (see non-goals: no auth), so any origin is permitted — mirrors the agent service, which also
 * opens CORS to all origins.
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("*");
  }
}
