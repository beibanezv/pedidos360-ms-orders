package com.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;

/** Salida de orders hacia productos; motivo EXPIRADA|CANCELADA únicamente. */
public record StockLiberadoEvent(
    UUID eventoId, UUID ordenId, String usuarioId, String motivo, Instant ocurrioEn) {}
