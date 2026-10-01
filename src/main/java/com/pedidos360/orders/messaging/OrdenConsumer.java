package com.pedidos360.orders.messaging;

import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del dominio "órdenes". Registra la orden y publica
 * orden.registrada para los 3 suscriptores del topic.
 *
 * Decisiones de error (indicador 5):
 * - Evento inválido (nulos, ítems vacíos, total negativo) → PERMANENTE,
 *   nunca va a pasar → DLQ directo.
 * - Orden duplicada (misma PK, entrega at-least-once) → se hace ACK sin
 *   guardar ni republicar: no es error, es idempotencia.
 * - Fallo transitorio (BD caída, broker caído al publicar) → requeue hasta
 *   MAX_REINTENTOS muertes, después DLQ.
 */
@Component
public class OrdenConsumer extends BaseConsumer {

  private final OrdenRepository ordenRepository;
  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;

  public OrdenConsumer(OrdenRepository ordenRepository,
      RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
    this.ordenRepository = ordenRepository;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
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
      if (ordenRepository.existsById(event.ordenId())) {
        return; // Duplicada por reintento: ACK sin efectos secundarios.
      }
      ordenRepository.save(new Orden(
          event.ordenId(), event.usuarioId(), event.emailComprador(), event.totalClp(),
          itemsJson(event)));
      rabbitTemplate.convertAndSend(
          RabbitMQConfig.EVENTOS_EXCHANGE,
          RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY,
          new OrdenRegistradaEvent(event.ordenId(), event.usuarioId(), event.emailComprador(),
              items(event), event.codigoCupon()));
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

  private String itemsJson(OrdenCreadaEvent event) {
    try {
      return objectMapper.writeValueAsString(event.items());
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("items no serializables: " + event, e);
    }
  }
}
