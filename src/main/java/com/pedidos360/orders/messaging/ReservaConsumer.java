package com.pedidos360.orders.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.OrdenReservaService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Una sola cola con dos routing keys (la declara M06a): se lee la key del
 * header y se convierte al DTO correspondiente. ACK manual vía BaseConsumer;
 * rechazo durable = marcador SIN_STOCK + outbox, nunca requeue.
 */
@Component
public class ReservaConsumer extends BaseConsumer {

  private final OrdenReservaService service;
  private final ObjectMapper objectMapper;

  public ReservaConsumer(OrdenReservaService service, ObjectMapper objectMapper) {
    this.service = service;
    this.objectMapper = objectMapper;
  }

  @Override
  protected int maxReintentos() {
    return RabbitMQConfig.MAX_REINTENTOS;
  }

  @RabbitListener(queues = OrdenTopologia.RESERVAS_ORDENES_QUEUE)
  public void consumirReserva(Message raw, Channel channel,
      @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
    consumir(channel, tag, raw, () -> {
      String key = raw.getMessageProperties().getReceivedRoutingKey();
      String json = new String(raw.getBody(), StandardCharsets.UTF_8);
      try {
        if (OrdenTopologia.STOCK_RESERVADO_ROUTING_KEY.equals(key)) {
          service.stockReservado(objectMapper.readValue(json, StockReservadoEvent.class));
        } else if (OrdenTopologia.STOCK_RECHAZADO_ROUTING_KEY.equals(key)) {
          service.stockRechazado(objectMapper.readValue(json, StockRechazadoEvent.class));
        } else {
          throw new IllegalArgumentException("Routing key de stock desconocida: " + key);
        }
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Evento de stock inválido", e);
      }
    });
  }
}
