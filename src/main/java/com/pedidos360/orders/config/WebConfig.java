package com.pedidos360.orders.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS de lectura de ordenes para el frontend Angular.
 *
 * El navegador llama a este servicio desde otro origen (localhost:4200 en local,
 * la URL https del frontend en AWS) y manda header Authorization, asi que sin
 * esto el preflight OPTIONS queda sin Access-Control-Allow-Origin y la vista
 * "Mis ordenes" falla aunque el backend responda 200.
 *
 * Sin Spring Security a proposito (lo protege el authorizer JWT del API Gateway
 * en AWS); por eso el CORS va aqui y no en un SecurityFilterChain.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  /** Orígenes permitidos, separados por coma (env CORS_ALLOWED_ORIGINS en AWS). */
  @Value("${cors.allowed-origins:http://localhost:4200}")
  private String allowedOrigins;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOrigins(allowedOrigins.split(","))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type");
  }
}
