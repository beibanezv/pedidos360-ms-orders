package com.pedidos360.orders.controller;

import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import com.pedidos360.orders.config.IdentityConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ordenes")
public class OrdenController {

  private final OrdenRepository ordenRepository;

  public OrdenController(OrdenRepository ordenRepository) {
    this.ordenRepository = ordenRepository;
  }

  @GetMapping("/{ordenId}")
  public ResponseEntity<Map<String, Object>> porId(@PathVariable UUID ordenId, @AuthenticationPrincipal Jwt jwt) {
    String usuarioId = IdentityConfig.usuario(jwt);
    return ordenRepository.findById(ordenId)
        .filter(o -> usuarioId.equals(o.getUsuarioId()))
        .map(o -> ResponseEntity.ok(resumen(o)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping
  public List<Map<String, Object>> porUsuario(@AuthenticationPrincipal Jwt jwt) {
    return ordenRepository.findByUsuarioIdOrderByCreatedAtDesc(IdentityConfig.usuario(jwt)).stream()
        .map(this::resumen)
        .toList();
  }

  private Map<String, Object> resumen(Orden o) {
    return Map.of(
        "ordenId", o.getId(),
        "usuarioId", o.getUsuarioId(),
        "totalClp", o.getTotalClp(),
        "estado", o.getEstado(),
        "itemsJson", o.getItemsJson(),
        "createdAt", o.getCreatedAt().toString());
  }
}
