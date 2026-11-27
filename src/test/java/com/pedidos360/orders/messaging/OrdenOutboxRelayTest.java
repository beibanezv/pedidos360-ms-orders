package com.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.OrdenOutbox;
import com.pedidos360.orders.orden.OrdenOutboxRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

/**
 * Sin broker no se puede simular el ACK real: estos tests comprueban que el
 * eventoId es fijo y que SIN confirmación NUNCA se marca publicado_en. La
 * configuración de publisher-confirms se comprueba contra el properties real
 * que Maven empaqueta en el JAR (test que falla si la propiedad falta).
 */
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

  /** Broker no disponible en el test: convertAndSend falla rápido, sin completar el futuro. */
  private void brokerNoDisponible() {
    doThrow(new AmqpException("broker no disponible en el test")).when(rabbitTemplate)
        .convertAndSend(any(String.class), any(String.class), any(Object.class),
            any(CorrelationData.class));
  }

  @Test
  void configuracionActivaConfirmacionesCorrelacionadas() throws IOException {
    Properties properties = new Properties();
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
      assertNotNull(in, "application.properties debe estar en el classpath del JAR");
      properties.load(in);
    }
    assertEquals("correlated", properties.getProperty("spring.rabbitmq.publisher-confirm-type"));
  }

  @Test
  void reenvioUsaMismoEventoIdYNoMarcaSinConfirmacionDelBroker() throws Exception {
    UUID eventoId = UUID.nameUUIDFromBytes("reenvio".getBytes());
    OrdenRegistradaEvent salida = new OrdenRegistradaEvent(UUID.randomUUID(), "usuario-1",
        "comprador@mail.com", List.of(), null);
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenReturn(List.of(pendiente(eventoId, RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY, salida)));
    brokerNoDisponible();

    relay.publicarPendientes();

    ArgumentCaptor<CorrelationData> correlacion = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate).convertAndSend(eq(OrdenTopologia.EVENTOS_EXCHANGE),
        eq(RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY), any(Object.class), correlacion.capture());
    assertEquals(eventoId.toString(), correlacion.getValue().getId());
    verify(outboxRepository).incrementarIntentos(eventoId);
    verify(outboxRepository, never()).marcarPublicado(any(), any());
  }

  @Test
  void publicacionDeLiberacionUsaRoutingKeyYEventoIdFijos() throws Exception {
    UUID eventoId = UUID.nameUUIDFromBytes("liberacion".getBytes());
    StockLiberadoEvent salida = new StockLiberadoEvent(eventoId, UUID.randomUUID(), "usuario-1",
        "EXPIRADA", Instant.now());
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenReturn(List.of(pendiente(eventoId, OrdenTopologia.STOCK_LIBERADO_ROUTING_KEY, salida)));
    brokerNoDisponible();

    relay.publicarPendientes();

    ArgumentCaptor<CorrelationData> correlacion = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate).convertAndSend(eq(OrdenTopologia.EVENTOS_EXCHANGE),
        eq(OrdenTopologia.STOCK_LIBERADO_ROUTING_KEY), any(Object.class), correlacion.capture());
    assertEquals(eventoId.toString(), correlacion.getValue().getId());
    verify(outboxRepository, never()).marcarPublicado(any(), any());
  }

  @Test
  void routingKeyDesconocidaNoTumbaElRelay() throws Exception {
    UUID eventoId = UUID.randomUUID();
    when(outboxRepository.findByEstadoOrderByCreadaEnAsc(eq("PENDIENTE"), any(Pageable.class)))
        .thenReturn(List.of(pendiente(eventoId, "inventada.clave", "{}")));

    assertDoesNotThrow(relay::publicarPendientes);

    verifyNoInteractions(rabbitTemplate);
    verify(outboxRepository, never()).marcarPublicado(any(), any());
  }
}
