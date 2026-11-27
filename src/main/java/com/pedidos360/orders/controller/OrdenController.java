package com.pedidos360.orders.controller;

import com.pedidos360.orders.config.IdentityConfig;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import com.pedidos360.orders.orden.OrdenReservaService;
import com.pedidos360.orders.orden.OrdenReservaService.ResultadoCancelacion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ordenes")
public class OrdenController {

  private final OrdenRepository ordenRepository;
  private final OrdenReservaService service;

  public OrdenController(OrdenRepository ordenRepository, OrdenReservaService service) {
    this.ordenRepository = ordenRepository;
    this.service = service;
  }

  public record ClaimRequest(@NotNull UUID intentoId) {}

  /** Sin intentoId: el comprador no puede afirmar que no se llamó al proveedor. */
  public record CancelarRequest(@NotBlank String motivo) {}

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

  @PostMapping("/{ordenId}/pago/claim")
  public ResponseEntity<Map<String, Object>> claim(@PathVariable UUID ordenId,
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ClaimRequest body) {
    boolean ok = service.reclamarPago(ordenId, IdentityConfig.usuario(jwt), body.intentoId());
    if (!ok) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "intento no vigente"));
    return ResponseEntity.ok(Map.of("estado", "PAGO_PENDIENTE"));
  }

  @PostMapping("/{ordenId}/cancelar")
  public ResponseEntity<Map<String, Object>> cancelar(@PathVariable UUID ordenId,
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CancelarRequest body) {
    if (!"CANCELA".equals(body.motivo())) {
      return ResponseEntity.badRequest().body(Map.of("error", "motivo inválido"));
    }
    return switch (service.cancelar(ordenId, IdentityConfig.usuario(jwt))) {
      case CANCELADA -> ResponseEntity.ok(Map.of("estado", "CANCELADA"));
      case NO_ENCONTRADA -> ResponseEntity.notFound().build();
      case PAGO_PENDIENTE, TERMINAL ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "orden no cancelable"));
    };
  }

  private Map<String, Object> resumen(Orden o) {
    Map<String, Object> r = new HashMap<>();
    r.put("ordenId", o.getId());
    r.put("usuarioId", o.getUsuarioId());
    r.put("totalClp", o.getTotalClp());
    r.put("estado", o.getEstado());
    r.put("itemsJson", o.getItemsJson());
    r.put("createdAt", o.getCreatedAt().toString());
    r.put("reservaExpiraEn", o.getReservaExpiraEn() == null ? null : o.getReservaExpiraEn().toString());
    r.put("intentoActualId", o.getIntentoActualId() == null ? null : o.getIntentoActualId().toString());
    r.put("requiereRevision", o.isRequiereRevision());
    return r;
  }
}
