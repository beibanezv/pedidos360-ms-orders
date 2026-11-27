package com.pedidos360.orders.messaging;

import com.pedidos360.orders.orden.OrdenReservaService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Solo RESERVADA vencida; PAGO_PENDIENTE nunca se toca (G0 §7.1). */
@Component
public class OrdenExpirador {

  private final OrdenReservaService service;

  public OrdenExpirador(OrdenReservaService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${p360.ordenes.expiracion.delay-ms:60000}",
      initialDelayString = "${p360.ordenes.expiracion.delay-ms:60000}")
  public void expirar() {
    service.expirarReservas();
  }
}
