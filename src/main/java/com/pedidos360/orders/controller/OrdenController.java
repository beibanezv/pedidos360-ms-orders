package com.pedidos360.orders.controller;

import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura de órdenes para la vista "Mis órdenes" del frontend.
 * Sin Spring Security a propósito: en AWS lo protege el authorizer JWT
 * del API Gateway (igual que ms-admin). El usuarioId lo pone el cliente
 * autenticado; no hay dato sensible fuera de las órdenes propias.
 */
@RestController
@RequestMapping("/ordenes")
public class OrdenController {

  private final OrdenRepository ordenRepository;

  public OrdenController(OrdenRepository ordenRepository) {
    this.ordenRepository = ordenRepository;
  }

  @GetMapping("/{ordenId}")
  public ResponseEntity<Map<String, Object>> porId(@PathVariable UUID ordenId) {
    return ordenRepository.findById(ordenId)
        .map(o -> ResponseEntity.ok(resumen(o)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping
  public List<Map<String, Object>> porUsuario(@RequestParam String usuarioId) {
    return ordenRepository.findByUsuarioIdOrderByCreatedAtDesc(usuarioId).stream()
        .map(this::resumen)
        .toList();
  }

  private Map<String, Object> resumen(Orden o) {
    return Map.of(
        "ordenId", o.getId(),
        "usuarioId", o.getUsuarioId(),
        "totalClp", o.getTotalClp(),
        "estado", o.getEstado(),
        "createdAt", o.getCreatedAt().toString());
  }
}
