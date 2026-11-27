package com.pedidos360.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pedidos360.orders.messaging.OrdenCreadaEvent;
import com.pedidos360.orders.messaging.OrdenRegistradaEvent;
import com.pedidos360.orders.messaging.PagoResueltoEvent;
import com.pedidos360.orders.messaging.StockLiberadoEvent;
import com.pedidos360.orders.messaging.StockRechazadoEvent;
import com.pedidos360.orders.messaging.StockReservadoEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.MessageConverter;

/** M06: todos los espejos locales se tipan por nombre simple. */
class RabbitMQConfigDtoTest {

  private static final String TYPE_ID = DefaultJackson2JavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME;
  private final MessageConverter converter = new RabbitMQConfig().jsonMessageConverter();

  @Test
  void mapperUsaTypeIdYMapeaTodosLosEspejosSinComodines() {
    DefaultJackson2JavaTypeMapper mapper = RabbitMQConfig.jsonTypeMapper();

    assertThat(mapper.getTypePrecedence()).isEqualTo(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
    assertThat(mapper.getIdClassMapping())
        .containsEntry("OrdenCreadaEvent", OrdenCreadaEvent.class)
        .containsEntry("OrdenRegistradaEvent", OrdenRegistradaEvent.class)
        .containsEntry("StockReservadoEvent", StockReservadoEvent.class)
        .containsEntry("StockRechazadoEvent", StockRechazadoEvent.class)
        .containsEntry("StockLiberadoEvent", StockLiberadoEvent.class)
        .containsEntry("PagoResueltoEvent", PagoResueltoEvent.class)
        .containsEntry("com.pedidos360.carrito.messaging.OrdenCreadaEvent", OrdenCreadaEvent.class)
        .containsEntry("com.pedidos360.pagos.messaging.PagoResueltoEvent", PagoResueltoEvent.class)
        .doesNotContainKey("*");
  }

  @Test
  void ordenCreada_producirYConsumirConservaTipoYCampos() {
    OrdenCreadaEvent evento = new OrdenCreadaEvent(orden(), "u-1", "comprador@example.cl",
        List.of(new OrdenCreadaEvent.Item(producto(), 2, 1990L)), 3980L, "CUPON10");

    verificar(evento, "OrdenCreadaEvent", "com.pedidos360.carrito.messaging.OrdenCreadaEvent");
  }

  @Test
  void ordenRegistrada_producirYConsumirConservaTipoYCampos() {
    OrdenRegistradaEvent evento = new OrdenRegistradaEvent(orden(), "u-1", "comprador@example.cl",
        List.of(new OrdenRegistradaEvent.Item(producto(), 2)), "CUPON10");

    verificar(evento, "OrdenRegistradaEvent",
        "com.pedidos360.productos.messaging.OrdenRegistradaEvent");
  }

  @Test
  void stockReservado_producirYConsumirConservaTipoYCampos() {
    verificar(new StockReservadoEvent(evento(), orden(), "u-1", ocurrioEn()),
        "StockReservadoEvent", "com.pedidos360.productos.messaging.StockReservadoEvent");
  }

  @Test
  void stockRechazado_producirYConsumirConservaTipoYCampos() {
    verificar(new StockRechazadoEvent(evento(), orden(), "u-1", "SIN_STOCK", ocurrioEn()),
        "StockRechazadoEvent", "com.pedidos360.productos.messaging.StockRechazadoEvent");
  }

  @Test
  void stockLiberado_producirYConsumirConservaTipoYCampos() {
    verificar(new StockLiberadoEvent(evento(), orden(), "u-1", "EXPIRADA", ocurrioEn()),
        "StockLiberadoEvent", "com.pedidos360.productos.messaging.StockLiberadoEvent");
  }

  @Test
  void pagoResuelto_producirYConsumirConservaLosSeisCampos() {
    PagoResueltoEvent evento = new PagoResueltoEvent(evento(), intento(), orden(), "u-1",
        "APROBADO", ocurrioEn());

    verificar(evento, "PagoResueltoEvent", "com.pedidos360.pagos.messaging.PagoResueltoEvent");
  }

  private void verificar(Object evento, String nombreSimple, String aliasFqcn) {
    Message mensaje = converter.toMessage(evento, new MessageProperties());

    assertThat((Object) mensaje.getMessageProperties().getHeader(TYPE_ID)).isEqualTo(nombreSimple);
    Object resultado = converter.fromMessage(mensaje);
    assertThat(resultado).isExactlyInstanceOf(evento.getClass()).isEqualTo(evento);

    MessageProperties propsAlias = new MessageProperties();
    propsAlias.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    propsAlias.setHeader(TYPE_ID, aliasFqcn);
    Object resultadoAlias = converter.fromMessage(new Message(mensaje.getBody(), propsAlias));
    assertThat(resultadoAlias)
        .isExactlyInstanceOf(evento.getClass())
        .isEqualTo(evento);
  }

  private static UUID orden() { return UUID.fromString("11111111-1111-1111-1111-111111111111"); }
  private static UUID producto() { return UUID.fromString("22222222-2222-2222-2222-222222222222"); }
  private static UUID evento() { return UUID.fromString("33333333-3333-3333-3333-333333333333"); }
  private static UUID intento() { return UUID.fromString("44444444-4444-4444-4444-444444444444"); }
  private static Instant ocurrioEn() { return Instant.parse("2026-11-27T10:15:30Z"); }
}
