package com.pedidos360.orders.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología RabbitMQ que usa ms-orders (rúbrica EP3/EP4 indicadores 1 y 2).
 * Nombres centralizados aquí: ningún otro archivo del servicio repite literales.
 *
 * Rutas documentadas:
 *   ms-carrito --direct--> p360.ordenes.exchange / orden.creada
 *                              └─> p360.ordenes.queue (la consume OrdenConsumer)
 *   ms-orders  --topic---> p360.eventos / orden.registrada
 *                              ├─> p360.stock.queue (ms-productos)
 *                              ├─> p360.notificaciones.queue (ms-notificaciones)
 *                              └─> p360.auditoria.queue (ms-auditoria, Fase 4)
 *   Si un mensaje muere (rechazo/NACK sin requeue) va a p360.dlx
 *   con routing key = nombre de la cola origen, y de ahí a su DLQ.
 */
@Configuration
public class RabbitMQConfig {

  // Caso de uso "registrar orden": consume orden.creada del direct.
  public static final String ORDENES_EXCHANGE = "p360.ordenes.exchange";
  public static final String ORDENES_QUEUE = "p360.ordenes.queue";
  public static final String ORDEN_CREADA_ROUTING_KEY = "orden.creada";

  // Caso de uso "orden registrada": publica al topic para los 3 suscriptores.
  public static final String EVENTOS_EXCHANGE = "p360.eventos";
  public static final String ORDEN_REGISTRADA_ROUTING_KEY = "orden.registrada";

  // C3/C4 congelado (G0 2026-11-27): colas nuevas sobre los exchanges existentes.
  public static final String STOCK_RESERVADO_ROUTING_KEY = "stock.reservado";
  public static final String STOCK_RECHAZADO_ROUTING_KEY = "stock.rechazado";
  public static final String PAGO_RESUELTO_ROUTING_KEY = "pago.resuelto";
  public static final String RESERVAS_ORDENES_QUEUE = "p360.reservas.ordenes.queue";
  public static final String RESERVAS_ORDENES_DLQ = "p360.reservas.ordenes.dlq";
  public static final String PAGOS_ORDENES_QUEUE = "p360.pagos.ordenes.queue";
  public static final String PAGOS_ORDENES_DLQ = "p360.pagos.ordenes.dlq";

  // Límite de reintentos de errores transitorios (ver OrdenConsumer).
  public static final int MAX_REINTENTOS = 3;

  // Dead-letter exchange compartido (direct: cada DLQ se enlaza con su propia key).
  public static final String DLX_EXCHANGE = "p360.dlx";
  public static final String ORDENES_DLQ = "p360.ordenes.dlq";

  // Declarar lo mismo que declara el productor es idempotente en RabbitMQ
  // (mismos argumentos): así la cola existe aunque ms-carrito aún no arrancó.
  @Bean
  DirectExchange ordenesExchange() {
    return new DirectExchange(ORDENES_EXCHANGE, true, false);
  }

  @Bean
  Queue ordenesQueue() {
    return QueueBuilder.durable(ORDENES_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", ORDENES_QUEUE)
        .build();
  }

  @Bean
  Binding ordenesBinding(Queue ordenesQueue, DirectExchange ordenesExchange) {
    return new Binding(ORDENES_QUEUE, Binding.DestinationType.QUEUE,
        ORDENES_EXCHANGE, ORDEN_CREADA_ROUTING_KEY, null);
  }

  @Bean
  DirectExchange deadLetterExchange() {
    return new DirectExchange(DLX_EXCHANGE, true, false);
  }

  @Bean
  Queue ordenesDlq() {
    return QueueBuilder.durable(ORDENES_DLQ).build();
  }

  @Bean
  Binding ordenesDlqBinding(Queue ordenesDlq, DirectExchange deadLetterExchange) {
    return new Binding(ORDENES_DLQ, Binding.DestinationType.QUEUE,
        DLX_EXCHANGE, ORDENES_QUEUE, null);
  }

  // Exchange de publicación: durable para que sobreviva reinicios del broker.
  @Bean
  TopicExchange eventosExchange() {
    return new TopicExchange(EVENTOS_EXCHANGE, true, false);
  }

  // Matías declara solo la topología C3; los consumidores los escribe Benjamín.
  @Bean
  Queue reservasOrdenesQueue() {
    return QueueBuilder.durable(RESERVAS_ORDENES_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", RESERVAS_ORDENES_QUEUE)
        .build();
  }

  @Bean
  Binding reservasOrdenesReservadoBinding(Queue reservasOrdenesQueue, TopicExchange eventosExchange) {
    return new Binding(RESERVAS_ORDENES_QUEUE, Binding.DestinationType.QUEUE,
        EVENTOS_EXCHANGE, STOCK_RESERVADO_ROUTING_KEY, null);
  }

  @Bean
  Binding reservasOrdenesRechazadoBinding(Queue reservasOrdenesQueue, TopicExchange eventosExchange) {
    return new Binding(RESERVAS_ORDENES_QUEUE, Binding.DestinationType.QUEUE,
        EVENTOS_EXCHANGE, STOCK_RECHAZADO_ROUTING_KEY, null);
  }

  @Bean
  Queue reservasOrdenesDlq() {
    return QueueBuilder.durable(RESERVAS_ORDENES_DLQ).build();
  }

  @Bean
  Binding reservasOrdenesDlqBinding(Queue reservasOrdenesDlq, DirectExchange deadLetterExchange) {
    return new Binding(RESERVAS_ORDENES_DLQ, Binding.DestinationType.QUEUE,
        DLX_EXCHANGE, RESERVAS_ORDENES_QUEUE, null);
  }

  @Bean
  Queue pagosOrdenesQueue() {
    return QueueBuilder.durable(PAGOS_ORDENES_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", PAGOS_ORDENES_QUEUE)
        .build();
  }

  @Bean
  Binding pagosOrdenesBinding(Queue pagosOrdenesQueue, TopicExchange eventosExchange) {
    return new Binding(PAGOS_ORDENES_QUEUE, Binding.DestinationType.QUEUE,
        EVENTOS_EXCHANGE, PAGO_RESUELTO_ROUTING_KEY, null);
  }

  @Bean
  Queue pagosOrdenesDlq() {
    return QueueBuilder.durable(PAGOS_ORDENES_DLQ).build();
  }

  @Bean
  Binding pagosOrdenesDlqBinding(Queue pagosOrdenesDlq, DirectExchange deadLetterExchange) {
    return new Binding(PAGOS_ORDENES_DLQ, Binding.DestinationType.QUEUE,
        DLX_EXCHANGE, PAGOS_ORDENES_QUEUE, null);
  }

  // Los eventos viajan como JSON (records de messaging.*).
  @Bean
  MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}
