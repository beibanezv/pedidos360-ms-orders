package com.pedidos360.orders.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Espejo del evento que publica ms-carrito (contrato propio por servicio:
 * solo importan los nombres de los campos JSON, no la clase).
 * codigoCupon es opcional (null = compra sin cupón).
 */
public record OrdenCreadaEvent(
    UUID ordenId,
    String usuarioId,
    List<Item> items,
    long totalClp,
    String codigoCupon) {

  public record Item(UUID productoId, int cantidad, long precioUnitarioClp) {}
}
