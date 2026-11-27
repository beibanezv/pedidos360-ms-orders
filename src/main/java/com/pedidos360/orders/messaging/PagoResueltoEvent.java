package com.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;

/** Espejo local del resultado terminal de pagos (resultado APROBADO|RECHAZADO). */
public record PagoResueltoEvent(
    UUID eventoId, UUID intentoId, UUID ordenId, String usuarioId,
    String resultado, Instant ocurrioEn) {}
