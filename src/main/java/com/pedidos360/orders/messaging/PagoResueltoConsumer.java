package com.pedidos360.orders.messaging;

import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.OrdenReservaService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Resultado terminal de pagos: CAS por intentoActualId en el servicio. */
@Component
public class PagoResueltoConsumer extends BaseConsumer {

  private final OrdenReservaService service;

  public PagoResueltoConsumer(OrdenReservaService service) {
    this.service = service;
  }

  @Override
  protected int maxReintentos() {
    return RabbitMQConfig.MAX_REINTENTOS;
  }

  @RabbitListener(queues = OrdenTopologia.PAGOS_ORDENES_QUEUE)
  public void resolverPago(PagoResueltoEvent event, Channel channel,
      @Header(AmqpHeaders.DELIVERY_TAG) long tag, Message raw) throws IOException {
    consumir(channel, tag, raw, () -> service.pagoResuelto(event));
  }
}
