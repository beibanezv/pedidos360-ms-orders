package com.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;

/** Espejo local del rechazo durable de ms-productos (motivo SIN_STOCK|SKU_NO_DISPONIBLE). */
public record StockRechazadoEvent(
    UUID eventoId, UUID ordenId, String usuarioId, String motivo, Instant ocurrioEn) {}
