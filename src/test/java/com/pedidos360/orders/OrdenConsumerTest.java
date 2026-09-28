package com.pedidos360.orders.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class OrdenConsumerTest {

  @Mock
  OrdenRepository ordenRepository;
  @Mock
  RabbitTemplate rabbitTemplate;
  @Spy
  ObjectMapper objectMapper = new ObjectMapper();
  @Mock
  Channel channel;

  @InjectMocks
  OrdenConsumer consumer;

  private static final long TAG = 7L;

  private static OrdenCreadaEvent eventoValido() {
    return new OrdenCreadaEvent(
        UUID.randomUUID(), "usuario-1",
        List.of(new OrdenCreadaEvent.Item(UUID.randomUUID(), 2, 19990L)),
        39980L, null);
  }

  private static Message mensajeFresco() {
    return new Message(new byte[0], new MessageProperties());
  }

  private static Message mensajeConMuertes(long muertes) {
    MessageProperties props = new MessageProperties();
    props.setHeader("x-death", List.of(Map.of("count", muertes)));
    return new Message(new byte[0], props);
  }

  @Test
  void exito_guardaPublicaYack() throws Exception {
    OrdenCreadaEvent event = eventoValido();
    when(ordenRepository.existsById(event.ordenId())).thenReturn(false);

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    verify(ordenRepository).save(any(Orden.class));
    verify(rabbitTemplate).convertAndSend(
        eq(RabbitMQConfig.EVENTOS_EXCHANGE),
        eq(RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY),
        any(OrdenRegistradaEvent.class));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void duplicada_ackSinEfectos() throws Exception {
    OrdenCreadaEvent event = eventoValido();
    when(ordenRepository.existsById(event.ordenId())).thenReturn(true);

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    verify(ordenRepository, never()).save(any(Orden.class));
    verify(rabbitTemplate, never()).convertAndSend(any(), any(), (Object) any());
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void invalida_dlqDirecto() throws Exception {
    OrdenCreadaEvent event = new OrdenCreadaEvent(
        UUID.randomUUID(), "usuario-1", List.of(), 0L, null);

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    verify(ordenRepository, never()).save(any(Orden.class));
    verify(channel).basicNack(TAG, false, false);
  }

  @Test
  void transitorio_reintenta() throws Exception {
    OrdenCreadaEvent event = eventoValido();
    when(ordenRepository.existsById(event.ordenId())).thenReturn(false);
    when(ordenRepository.save(any(Orden.class))).thenThrow(new RuntimeException("BD caída"));

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    verify(channel).basicNack(TAG, false, true);
  }

  @Test
  void transitorioAgotado_dlq() throws Exception {
    OrdenCreadaEvent event = eventoValido();
    when(ordenRepository.existsById(event.ordenId())).thenReturn(false);
    when(ordenRepository.save(any(Orden.class))).thenThrow(new RuntimeException("BD caída"));

    consumer.registrarOrden(event, channel, TAG, mensajeConMuertes(3));

    verify(channel).basicNack(TAG, false, false);
  }
}
