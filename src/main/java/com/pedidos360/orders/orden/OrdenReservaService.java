package com.pedidos360.orders.orden;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.messaging.OrdenCreadaEvent;
import com.pedidos360.orders.messaging.OrdenRegistradaEvent;
import com.pedidos360.orders.messaging.OrdenTopologia;
import com.pedidos360.orders.messaging.PagoResueltoEvent;
import com.pedidos360.orders.messaging.StockLiberadoEvent;
import com.pedidos360.orders.messaging.StockRechazadoEvent;
import com.pedidos360.orders.messaging.StockReservadoEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decisiones de reserva/pago/compensación en una transacción real: los
 * consumidores no pueden auto-invocar @Transactional. Toda salida se deja en
 * ordenes_outbox con un eventoId fijo antes del ACK; el relay la publica y
 * marca publicado_en solo con confirmación del broker.
 */
@Service
public class OrdenReservaService {

  private static final int MINUTOS_RESERVA = 30;

  private final OrdenRepository ordenRepository;
  private final OrdenOutboxRepository outboxRepository;
  private final ProcesadoRepository procesadoRepository;
  private final ObjectMapper objectMapper;

  public OrdenReservaService(OrdenRepository ordenRepository, OrdenOutboxRepository outboxRepository,
      ProcesadoRepository procesadoRepository, ObjectMapper objectMapper) {
    this.ordenRepository = ordenRepository;
    this.outboxRepository = outboxRepository;
    this.procesadoRepository = procesadoRepository;
    this.objectMapper = objectMapper;
  }

  public enum ResultadoCancelacion { CANCELADA, PAGO_PENDIENTE, NO_ENCONTRADA, TERMINAL }

  @Transactional
  public void registrar(OrdenCreadaEvent event, OrdenRegistradaEvent salida) {
    UUID ordenId = event.ordenId();
    UUID eventoId = idFijo("orden.registrada", ordenId);
    if (ordenRepository.existsById(ordenId)) {
      // Duplicado: no se inventa otra salida; se reproduce la pendiente con el mismo id.
      OrdenOutbox previa = outboxRepository.findById(eventoId).orElse(null);
      if (previa != null && !"PENDIENTE".equals(previa.getEstado())) {
        outboxRepository.marcarPendiente(eventoId);
      }
      return;
    }
    String itemsJson = json(event.items());
    String payload = json(salida);
    ordenRepository.save(new Orden(ordenId, event.usuarioId(), event.emailComprador(),
        event.totalClp(), itemsJson));
    outboxRepository.save(new OrdenOutbox(eventoId, RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY,
        payload, Instant.now()));
  }

  @Transactional
  public void stockReservado(StockReservadoEvent event) {
    ProcesadoId procesadoId = new ProcesadoId("STOCK", event.eventoId());
    if (procesadoRepository.existsById(procesadoId)) return;

    Orden orden = ordenRepository.findById(event.ordenId())
        .orElseThrow(() -> new IllegalArgumentException("Orden desconocida: " + event.ordenId()));
    Instant ahora = Instant.now();
    switch (orden.getEstado()) {
      case "REGISTRADA" -> {
        orden.setEstado("RESERVADA");
        orden.setReservaExpiraEn(event.ocurrioEn().plus(MINUTOS_RESERVA, java.time.temporal.ChronoUnit.MINUTES));
        ordenRepository.save(orden);
      }
      case "CANCELADA", "EXPIRADA" -> {
        // Un rechazo previo NO retiene stock; sí lo retienen un intento vigente o un aprobado.
        if (orden.getIntentoActualId() == null
            && !procesadoRepository.existeAprobado("PAGO", "APROBADO", orden.getId())) {
          liberar(orden, orden.getEstado(), ahora);
        } else {
          orden.setRequiereRevision(true);
          ordenRepository.save(orden);
        }
      }
      case "PAGO_PENDIENTE", "PAGO_APROBADO" -> {
        // Incoherencia nueva: retener, nunca liberar ni degradar.
        orden.setRequiereRevision(true);
        ordenRepository.save(orden);
      }
      default -> { /* RESERVADA o SIN_STOCK: ACK sin efecto */ }
    }
    procesadoRepository.save(new Procesado(procesadoId, orden.getId(), orden.getEstado(), ahora));
  }

  @Transactional
  public void stockRechazado(StockRechazadoEvent event) {
    if (!"SIN_STOCK".equals(event.motivo()) && !"SKU_NO_DISPONIBLE".equals(event.motivo())) {
      throw new IllegalArgumentException("Motivo de rechazo inválido: " + event.motivo());
    }
    ProcesadoId procesadoId = new ProcesadoId("STOCK", event.eventoId());
    if (procesadoRepository.existsById(procesadoId)) return;

    Orden orden = ordenRepository.findById(event.ordenId())
        .orElseThrow(() -> new IllegalArgumentException("Orden desconocida: " + event.ordenId()));
    if ("REGISTRADA".equals(orden.getEstado())) {
      // Rechazo durable: la orden deja de ser pagable; reintento = nuevo checkout.
      orden.setEstado("SIN_STOCK");
      ordenRepository.save(orden);
    }
    procesadoRepository.save(new Procesado(procesadoId, orden.getId(), event.motivo(), Instant.now()));
  }

  @Transactional
  public void pagoResuelto(PagoResueltoEvent event) {
    String resultado = event.resultado();
    if (!"APROBADO".equals(resultado) && !"RECHAZADO".equals(resultado)) {
      throw new IllegalArgumentException("Resultado de pago inválido: " + resultado);
    }
    ProcesadoId procesadoId = new ProcesadoId("PAGO", event.intentoId());
    if (procesadoRepository.existsById(procesadoId)) return;

    Orden orden = ordenRepository.findById(event.ordenId())
        .orElseThrow(() -> new IllegalArgumentException("Orden desconocida: " + event.ordenId()));
    if (!event.intentoId().equals(orden.getIntentoActualId())) {
      aplicarOtroIntento(orden, event, procesadoId);
      return;
    }
    if (!"PAGO_PENDIENTE".equals(orden.getEstado())) {
      // Mismo intento pero la orden ya avanzó: nunca degradar un estado terminal.
      orden.setRequiereRevision(true);
      ordenRepository.save(orden);
      procesadoRepository.save(new Procesado(procesadoId, orden.getId(), resultado, Instant.now()));
      return;
    }

    int filas = ordenRepository.resolverPago(orden.getId(), event.intentoId(), resultado,
        "APROBADO".equals(resultado) ? "PAGO_APROBADO" : "RESERVADA",
        Instant.now().plus(MINUTOS_RESERVA, java.time.temporal.ChronoUnit.MINUTES));
    if (filas == 1) {
      procesadoRepository.save(new Procesado(procesadoId, orden.getId(), resultado, Instant.now()));
      return;
    }
    // CAS sin efecto: re-leer y aplicar reglas; nunca forzar el cambio.
    Orden actual = ordenRepository.findById(orden.getId())
        .orElseThrow(() -> new IllegalStateException("Orden desapareció al resolver pago"));
    if ("PAGO_APROBADO".equals(actual.getEstado())) {
      procesadoRepository.save(new Procesado(procesadoId, actual.getId(), "APROBADO", Instant.now()));
    } else if (!event.intentoId().equals(actual.getIntentoActualId())) {
      aplicarOtroIntento(actual, event, procesadoId);
    } else {
      throw new IllegalStateException("CAS de pago sin efecto; se reintentará");
    }
  }

  private void aplicarOtroIntento(Orden orden, PagoResueltoEvent event, ProcesadoId procesadoId) {
    if ("APROBADO".equals(event.resultado())) {
      // Defensa ante incoherencia del proveedor: retener stock y revisión humana.
      orden.setRequiereRevision(true);
      ordenRepository.save(orden);
    }
    procesadoRepository.save(new Procesado(procesadoId, orden.getId(), event.resultado(), Instant.now()));
  }

  /** Claim idempotente por dueño + intento; 0 filas obliga a re-leer antes de 409. */
  @Transactional
  public boolean reclamarPago(UUID ordenId, String usuarioId, UUID intentoId) {
    if (intentoId == null) throw new IllegalArgumentException("intentoId obligatorio");
    if (ordenRepository.findByIdAndUsuarioIdAndEstadoAndIntentoActualId(
        ordenId, usuarioId, "PAGO_PENDIENTE", intentoId).isPresent()) {
      return true; // Misma petición ya atendida: sin crear pago ni extender vencimiento.
    }
    if (ordenRepository.reclamar(ordenId, usuarioId, intentoId, Instant.now()) == 1) return true;
    return ordenRepository.findById(ordenId)
        .filter(o -> usuarioId.equals(o.getUsuarioId()) && intentoId.equals(o.getIntentoActualId())
            && "PAGO_PENDIENTE".equals(o.getEstado()))
        .isPresent();
  }

  /** El cuerpo de cancelación nunca acepta intentoId ni desistencia afirmada. */
  @Transactional
  public ResultadoCancelacion cancelar(UUID ordenId, String usuarioId) {
    Orden orden = ordenRepository.findById(ordenId)
        .filter(o -> usuarioId.equals(o.getUsuarioId())).orElse(null);
    if (orden == null) return ResultadoCancelacion.NO_ENCONTRADA;
    if ("PAGO_PENDIENTE".equals(orden.getEstado())) return ResultadoCancelacion.PAGO_PENDIENTE;
    if ("PAGO_APROBADO".equals(orden.getEstado()) || "EXPIRADA".equals(orden.getEstado())) {
      return ResultadoCancelacion.TERMINAL;
    }
    if ("CANCELADA".equals(orden.getEstado())) return ResultadoCancelacion.CANCELADA;

    if ("RESERVADA".equals(orden.getEstado())) {
      if (ordenRepository.cancelarReservada(ordenId) == 1) {
        liberar(orden, "CANCELADA", Instant.now());
      } else {
        Orden actual = ordenRepository.findById(ordenId)
            .orElseThrow(() -> new IllegalStateException("Orden desapareció al cancelar"));
        if ("PAGO_PENDIENTE".equals(actual.getEstado())) return ResultadoCancelacion.PAGO_PENDIENTE;
      }
      return ResultadoCancelacion.CANCELADA;
    }
    // REGISTRADA (incluida la histórica) y SIN_STOCK: sin liberación inventada.
    ordenRepository.cancelarSinReserva(ordenId, usuarioId);
    return ResultadoCancelacion.CANCELADA;
  }

  @Transactional
  public void expirarReservas() {
    Instant ahora = Instant.now();
    List<Orden> vencidas = ordenRepository.findByEstadoAndReservaExpiraEnBefore(
        "RESERVADA", ahora, PageRequest.of(0, 50));
    for (Orden orden : vencidas) {
      if (ordenRepository.expirar(orden.getId(), ahora) == 1) {
        liberar(orden, "EXPIRADA", ahora);
      }
    }
  }

  private void liberar(Orden orden, String motivo, Instant ahora) {
    ProcesadoId idLiberacion = new ProcesadoId("LIBERADO", orden.getId());
    if (procesadoRepository.existsById(idLiberacion)) return; // doble liberación: stock intacto
    procesadoRepository.save(new Procesado(idLiberacion, orden.getId(), motivo, ahora));
    UUID eventoId = idFijo("stock.liberado:" + motivo, orden.getId());
    outboxRepository.save(new OrdenOutbox(eventoId, OrdenTopologia.STOCK_LIBERADO_ROUTING_KEY,
        json(new StockLiberadoEvent(eventoId, orden.getId(), orden.getUsuarioId(), motivo, ahora)), ahora));
  }

  private static UUID idFijo(String prefijo, UUID ordenId) {
    return UUID.nameUUIDFromBytes((prefijo + ":" + ordenId).getBytes(StandardCharsets.UTF_8));
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("No se pudo serializar la salida de la orden", e);
    }
  }
}
