package com.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.OrdenOutbox;
import com.pedidos360.orders.orden.OrdenOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

/** El relay marca publicado_en solo con ACK y reenvía siempre con el mismo eventoId. */
@ExtendWith(MockitoExtension.class)
class OrdenOutboxRelayTest {

  @Mock RabbitTemplate rabbitTemplate;
  @Mock OrdenOutboxRepository outboxRepository;

  ObjectMapper objectMapper;
  OrdenOutboxRelay relay;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    relay = new OrdenOutboxRelay(rabbitTemplate, outboxRepository, objectMapper);
  }

  private OrdenOutbox pendiente(UUID eventoId, String routingKey, Object payload) throws Exception {
    return new OrdenOutbox(eventoId, routingKey, objectMapper.writeValueAsString(payload), Instant.now());
  }

  private void confirmar(boolean ack) {
    doAnswer(inv -> {
      CorrelationData correlacion = inv.getArgument(3);
      correlacion.getFuture().complete(new Confirm(ack, ack ? null : "nack"));
      return null;
    }).when(rabbitTemplate).convertAndSend(
        any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
  }

  @Test
  void reenvioTrasCaidaUsaMismoEventoIdYMarcaTrasConfirm() throws Exception {
    UUID eventoId = UUID.nameUUIDFromBytes("reenvio".getBytes());
    OrdenRegistradaEvent salida = new OrdenRegistradaEvent(UUID.randomUUID(), "usuario-1",
        "comprador@mail.com", List.of(), null);
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenReturn(List.of(pendiente(eventoId, RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY, salida)));
    confirmar(true);

    relay.publicarPendientes();

    ArgumentCaptor<CorrelationData> correlacion = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate).convertAndSend(eq(OrdenTopologia.EVENTOS_EXCHANGE),
        eq(RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY), any(Object.class), correlacion.capture());
    assertEquals(eventoId.toString(), correlacion.getValue().getId());
    verify(outboxRepository).marcarPublicado(eq(eventoId), any());
    verify(outboxRepository, never()).incrementarIntentos(any());
  }

  @Test
  void sinConfirmNoMarcaPublicado() throws Exception {
    UUID eventoId = UUID.nameUUIDFromBytes("sin-confirm".getBytes());
    StockLiberadoEvent salida = new StockLiberadoEvent(eventoId, UUID.randomUUID(), "usuario-1",
        "EXPIRADA", Instant.now());
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenReturn(List.of(pendiente(eventoId, OrdenTopologia.STOCK_LIBERADO_ROUTING_KEY, salida)));
    confirmar(false);

    relay.publicarPendientes();

    verify(outboxRepository).incrementarIntentos(eventoId);
    verify(outboxRepository, never()).marcarPublicado(any(), any());
  }
}
