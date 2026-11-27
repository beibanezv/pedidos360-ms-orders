package com.pedidos360.orders.messaging;

import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.OrdenReservaService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del dominio "órdenes". Guarda la orden y su salida pendiente en
 * una sola transacción (bean separado) y publica tras el commit. El duplicado
 * ya no hace ACK sin publicar: reproduce la salida con el mismo eventoId.
 *
 * Decisiones de error (indicador 5):
 * - Evento inválido (nulos, ítems vacíos, total negativo) → PERMANENTE → DLQ.
 * - Fallo transitorio (BD caída, broker caído al publicar) → requeue hasta
 *   MAX_REINTENTOS muertes, después DLQ.
 */
@Component
public class OrdenConsumer extends BaseConsumer {

  private final OrdenReservaService service;
  private final OrdenOutboxRelay relay;

  public OrdenConsumer(OrdenReservaService service, OrdenOutboxRelay relay) {
    this.service = service;
    this.relay = relay;
  }

  @Override
  protected int maxReintentos() {
    return RabbitMQConfig.MAX_REINTENTOS;
  }

  @RabbitListener(queues = RabbitMQConfig.ORDENES_QUEUE)
  public void registrarOrden(OrdenCreadaEvent event, Channel channel,
      @Header(AmqpHeaders.DELIVERY_TAG) long tag, Message raw) throws IOException {
    consumir(channel, tag, raw, () -> {
      validar(event);
      service.registrar(event, new OrdenRegistradaEvent(event.ordenId(), event.usuarioId(),
          event.emailComprador(), items(event), event.codigoCupon()));
      relay.publicarPendientes();
    });
  }

  private static void validar(OrdenCreadaEvent event) {
    if (event == null || event.ordenId() == null
        || event.usuarioId() == null || event.usuarioId().isBlank()
        || event.emailComprador() == null || event.emailComprador().isBlank()
        || event.items() == null || event.items().isEmpty()
        || event.totalClp() < 0
        || event.items().stream().anyMatch(i ->
            i == null || i.productoId() == null || i.cantidad() <= 0)) {
      throw new IllegalArgumentException("orden.creada inválida: " + event);
    }
  }

  private static List<OrdenRegistradaEvent.Item> items(OrdenCreadaEvent event) {
    return event.items().stream()
        .map(i -> new OrdenRegistradaEvent.Item(i.productoId(), i.cantidad()))
        .toList();
  }
}
