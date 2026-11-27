package com.pedidos360.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

/** M06a: colas de reservas y pagos con el contrato C3/C4 congelado. */
class RabbitMQConfigC3Test {
  private final RabbitMQConfig config = new RabbitMQConfig();

  @Test
  void reservasYPagosSonDurablesConDlx() {
    Queue reservas = config.reservasOrdenesQueue();
    Queue pagos = config.pagosOrdenesQueue();

    assertThat(reservas.isDurable()).isTrue();
    assertThat(reservas.getArguments())
        .containsEntry("x-dead-letter-exchange", RabbitMQConfig.DLX_EXCHANGE)
        .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.RESERVAS_ORDENES_QUEUE);
    assertThat(pagos.isDurable()).isTrue();
    assertThat(pagos.getArguments())
        .containsEntry("x-dead-letter-exchange", RabbitMQConfig.DLX_EXCHANGE)
        .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.PAGOS_ORDENES_QUEUE);
  }

  @Test
  void reservasEscuchaReservadoYRechazadoYPagosEscuchaPagoResuelto() {
    Queue reservas = config.reservasOrdenesQueue();
    Queue pagos = config.pagosOrdenesQueue();

    assertThat(config.reservasOrdenesReservadoBinding(reservas, config.eventosExchange()).getRoutingKey())
        .isEqualTo("stock.reservado");
    assertThat(config.reservasOrdenesRechazadoBinding(reservas, config.eventosExchange()).getRoutingKey())
        .isEqualTo("stock.rechazado");
    assertThat(config.pagosOrdenesBinding(pagos, config.eventosExchange()).getRoutingKey())
        .isEqualTo("pago.resuelto");
    assertThat(config.pagosOrdenesBinding(pagos, config.eventosExchange()).getExchange())
        .isEqualTo(RabbitMQConfig.EVENTOS_EXCHANGE);
  }

  @Test
  void dlqsSeEnlazanConElNombreDeSuColaOrigen() {
    Binding reservasDlq = config.reservasOrdenesDlqBinding(
        config.reservasOrdenesDlq(), config.deadLetterExchange());
    Binding pagosDlq = config.pagosOrdenesDlqBinding(
        config.pagosOrdenesDlq(), config.deadLetterExchange());

    assertThat(reservasDlq.getRoutingKey()).isEqualTo(RabbitMQConfig.RESERVAS_ORDENES_QUEUE);
    assertThat(pagosDlq.getRoutingKey()).isEqualTo(RabbitMQConfig.PAGOS_ORDENES_QUEUE);
  }

  @Test
  void constantesDelContratoCongelado() {
    assertThat(RabbitMQConfig.RESERVAS_ORDENES_QUEUE).isEqualTo("p360.reservas.ordenes.queue");
    assertThat(RabbitMQConfig.PAGOS_ORDENES_QUEUE).isEqualTo("p360.pagos.ordenes.queue");
    assertThat(RabbitMQConfig.PAGO_RESUELTO_ROUTING_KEY).isEqualTo("pago.resuelto");
  }

  @Test
  void topologiaAnteriorSigueIgual() {
    Queue ordenes = config.ordenesQueue();

    assertThat(ordenes.getName()).isEqualTo("p360.ordenes.queue");
    assertThat(config.ordenesBinding(ordenes, config.ordenesExchange()).getRoutingKey()).isEqualTo("orden.creada");
  }
}
