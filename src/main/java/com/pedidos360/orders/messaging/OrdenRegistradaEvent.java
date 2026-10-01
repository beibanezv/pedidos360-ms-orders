package com.pedidos360.orders.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Evento que publica ms-orders al registrar una orden. Lo consumen
 * ms-productos (productoId/cantidad), ms-notificaciones (emailComprador),
 * ms-auditoria y ms-cupones (codigoCupon, nullable).
 * Solo viajan los campos que los consumidores necesitan.
 */
public record OrdenRegistradaEvent(
    UUID ordenId,
    String usuarioId,
    String emailComprador,
    List<Item> items,
    String codigoCupon) {

  public record Item(UUID productoId, int cantidad) {}
}
