package com.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pedidos360.orders.orden.OrdenReservaService;
import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class OrdenConsumerTest {

  @Mock
  OrdenReservaService service;
  @Mock
  OrdenOutboxRelay relay;
  @Mock
  Channel channel;

  @InjectMocks
  OrdenConsumer consumer;

  private static final long TAG = 7L;

  private static OrdenCreadaEvent eventoValido() {
    return new OrdenCreadaEvent(
        UUID.randomUUID(), "usuario-1", "comprador@mail.com",
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

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    ArgumentCaptor<OrdenCreadaEvent> entrada = ArgumentCaptor.forClass(OrdenCreadaEvent.class);
    ArgumentCaptor<OrdenRegistradaEvent> salida = ArgumentCaptor.forClass(OrdenRegistradaEvent.class);
    verify(service).registrar(entrada.capture(), salida.capture());
    assertEquals(event.ordenId(), entrada.getValue().ordenId());
    assertEquals("comprador@mail.com", salida.getValue().emailComprador());
    verify(relay).publicarPendientes();
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void duplicada_republicaSalidaPendiente() throws Exception {
    OrdenCreadaEvent event = eventoValido();

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    // Sin atajo existsById: el duplicado también pasa por el servicio y el relay.
    verify(service).registrar(any(), any());
    verify(relay).publicarPendientes();
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void invalida_dlqDirecto() throws Exception {
    OrdenCreadaEvent event = new OrdenCreadaEvent(
        UUID.randomUUID(), "usuario-1", "comprador@mail.com", List.of(), 0L, null);

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    verify(service, never()).registrar(any(), any());
    verify(relay, never()).publicarPendientes();
    verify(channel).basicNack(TAG, false, false);
  }

  @Test
  void sinEmail_dlqDirecto() throws Exception {
    OrdenCreadaEvent event = new OrdenCreadaEvent(
        UUID.randomUUID(), "usuario-1", "  ",
        List.of(new OrdenCreadaEvent.Item(UUID.randomUUID(), 1, 1000L)), 1000L, null);

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    verify(service, never()).registrar(any(), any());
    verify(channel).basicNack(TAG, false, false);
  }

  @Test
  void transitorio_reintenta() throws Exception {
    OrdenCreadaEvent event = eventoValido();
    doThrow(new RuntimeException("BD caída")).when(service).registrar(any(), any());

    consumer.registrarOrden(event, channel, TAG, mensajeFresco());

    verify(channel).basicNack(TAG, false, true);
  }

  @Test
  void transitorioAgotado_dlq() throws Exception {
    OrdenCreadaEvent event = eventoValido();
    doThrow(new RuntimeException("BD caída")).when(service).registrar(any(), any());

    consumer.registrarOrden(event, channel, TAG, mensajeConMuertes(3));

    verify(channel).basicNack(TAG, false, false);
  }
}
