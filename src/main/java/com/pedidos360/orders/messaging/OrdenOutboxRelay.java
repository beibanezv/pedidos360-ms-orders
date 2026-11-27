package com.pedidos360.orders.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.OrdenOutbox;
import com.pedidos360.orders.orden.OrdenOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publica la salida persistida tras el commit y marca publicado_en SOLO con
 * confirmación Rabbit. Reenvía pendientes tras reinicio con el mismo eventoId.
 * Requiere spring.rabbitmq.publisher-confirm-type=correlated (ver properties).
 */
@Component
public class OrdenOutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OrdenOutboxRelay.class);

  private final RabbitTemplate rabbitTemplate;
  private final OrdenOutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;

  public OrdenOutboxRelay(RabbitTemplate rabbitTemplate, OrdenOutboxRepository outboxRepository,
      ObjectMapper objectMapper) {
    this.rabbitTemplate = rabbitTemplate;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelayString = "${p360.ordenes.outbox.delay-ms:2000}",
      initialDelayString = "${p360.ordenes.outbox.delay-ms:2000}")
  public void publicarPendientes() {
    List<OrdenOutbox> pendientes = outboxRepository.findByEstadoOrderByCreadaEnAsc(
        "PENDIENTE", PageRequest.of(0, 20));
    for (OrdenOutbox salida : pendientes) {
      enviar(salida);
    }
  }

  private void enviar(OrdenOutbox salida) {
    Object evento;
    try {
      if (RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY.equals(salida.getRoutingKey())) {
        evento = objectMapper.readValue(salida.getPayload(), OrdenRegistradaEvent.class);
      } else if (OrdenTopologia.STOCK_LIBERADO_ROUTING_KEY.equals(salida.getRoutingKey())) {
        evento = objectMapper.readValue(salida.getPayload(), StockLiberadoEvent.class);
      } else {
        // Una fila mal escrita no debe tumbar el scheduler ni marcarse como enviada.
        log.error("Routing key desconocida en outbox eventoId={} key={}",
            salida.getEventoId(), salida.getRoutingKey());
        return;
      }
      // eventoId estable: el reenvío no crea un resultado nuevo.
      CorrelationData correlacion = new CorrelationData(salida.getEventoId().toString());
      rabbitTemplate.convertAndSend(OrdenTopologia.EVENTOS_EXCHANGE, salida.getRoutingKey(),
          evento, correlacion);
      Confirm confirm = correlacion.getFuture().get(5, TimeUnit.SECONDS);
      if (confirm != null && confirm.isAck()) {
        outboxRepository.marcarPublicado(salida.getEventoId(), Instant.now());
      } else {
        outboxRepository.incrementarIntentos(salida.getEventoId());
      }
    } catch (AmqpException | JsonProcessingException | InterruptedException
             | ExecutionException | TimeoutException ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      outboxRepository.incrementarIntentos(salida.getEventoId());
    }
  }
}
