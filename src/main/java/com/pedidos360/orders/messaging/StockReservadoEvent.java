package com.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;

/** Espejo local del evento que publica ms-productos (sin librería compartida). */
public record StockReservadoEvent(
    UUID eventoId, UUID ordenId, String usuarioId, Instant ocurrioEn) {}
