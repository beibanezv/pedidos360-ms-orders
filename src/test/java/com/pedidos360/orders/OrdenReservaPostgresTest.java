package com.pedidos360.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.messaging.OrdenCreadaEvent;
import com.pedidos360.orders.messaging.OrdenRegistradaEvent;
import com.pedidos360.orders.messaging.PagoResueltoEvent;
import com.pedidos360.orders.messaging.StockLiberadoEvent;
import com.pedidos360.orders.messaging.StockRechazadoEvent;
import com.pedidos360.orders.messaging.StockReservadoEvent;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenOutbox;
import com.pedidos360.orders.orden.OrdenOutboxRepository;
import com.pedidos360.orders.orden.OrdenRepository;
import com.pedidos360.orders.orden.OrdenReservaService;
import com.pedidos360.orders.orden.OrdenReservaService.ResultadoCancelacion;
import com.pedidos360.orders.orden.ProcesadoId;
import com.pedidos360.orders.orden.ProcesadoRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transiciones G0 sobre PostgreSQL DESCHABLE (nunca el RDS de negocio).
 * Requiere P360_TEST_POSTGRES_URL con una base creada para la prueba; si no
 * está definida JUnit reporta el bloque como OMITIDO.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrdenReservaService.class, OrdenReservaPostgresTest.MapperConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "P360_TEST_POSTGRES_URL", matches = ".+")
@TestPropertySource(properties = {
    "spring.datasource.url=${P360_TEST_POSTGRES_URL}",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never"
})
class OrdenReservaPostgresTest {

  private static final String USUARIO = "usuario-1";

  @TestConfiguration
  static class MapperConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }
  }

  @Autowired OrdenReservaService service;
  @Autowired OrdenRepository ordenes;
  @Autowired OrdenOutboxRepository outbox;
  @Autowired ProcesadoRepository procesados;

  @BeforeEach
  void limpiar() {
    outbox.deleteAllInBatch();
    procesados.deleteAllInBatch();
    ordenes.deleteAllInBatch();
  }

  private Orden guardar(String estado) {
    Orden o = new Orden(UUID.randomUUID(), USUARIO, "comprador@mail.com", 19990L, "[]");
    o.setEstado(estado);
    return ordenes.save(o);
  }

  private Orden recargar(UUID id) {
    return ordenes.findById(id).orElseThrow();
  }

  private OrdenCreadaEvent creada(UUID id) {
    return new OrdenCreadaEvent(id, USUARIO, "comprador@mail.com",
        List.of(new OrdenCreadaEvent.Item(UUID.randomUUID(), 1, 19990L)), 19990L, null);
  }

  private OrdenRegistradaEvent registrada(UUID id) {
    return new OrdenRegistradaEvent(id, USUARIO, "comprador@mail.com",
        List.of(new OrdenRegistradaEvent.Item(UUID.randomUUID(), 1)), null);
  }

  private StockReservadoEvent reservado(UUID ordenId, UUID eventoId) {
    return new StockReservadoEvent(eventoId, ordenId, USUARIO, Instant.now());
  }

  private PagoResueltoEvent pago(UUID ordenId, UUID intentoId, String resultado) {
    return new PagoResueltoEvent(UUID.randomUUID(), intentoId, ordenId, USUARIO, resultado, Instant.now());
  }

  @Test
  void guardarOrdenYCaidaAntesDePublicarDuplicadoReproduceSalida() {
    UUID id = UUID.randomUUID();
    UUID eventoId = UUID.nameUUIDFromBytes(("orden.registrada:" + id).getBytes(StandardCharsets.UTF_8));

    service.registrar(creada(id), registrada(id));
    OrdenOutbox pendiente = outbox.findById(eventoId).orElseThrow();
    assertNull(pendiente.getPublicadoEn());
    assertEquals("orden.registrada", pendiente.getRoutingKey());

    // Caída antes de publicar: el reenvío del mismo orden.creada no crea otra salida.
    service.registrar(creada(id), registrada(id));

    assertEquals(1, outbox.count());
    assertEquals(eventoId, outbox.findAll().get(0).getEventoId());
    assertEquals(1, ordenes.count());
  }

  @Test
  void stockReservadoDuplicadoRepublicaSinCambiarEstado() {
    Orden o = guardar("REGISTRADA");
    UUID eventoId = UUID.randomUUID();

    service.stockReservado(reservado(o.getId(), eventoId));
    Instant expira = recargar(o.getId()).getReservaExpiraEn();
    service.stockReservado(reservado(o.getId(), eventoId));

    assertEquals("RESERVADA", recargar(o.getId()).getEstado());
    assertEquals(expira, recargar(o.getId()).getReservaExpiraEn());
    assertTrue(procesados.existsById(new ProcesadoId("STOCK", eventoId)));
  }

  @Test
  void rechazoStockDurableHaceOrdenNoPagable() {
    Orden o = guardar("REGISTRADA");

    service.stockRechazado(new StockRechazadoEvent(UUID.randomUUID(), o.getId(), USUARIO,
        "SIN_STOCK", Instant.now()));

    assertEquals("SIN_STOCK", recargar(o.getId()).getEstado());
    assertNull(service.reclamarPago(o.getId(), USUARIO, UUID.randomUUID()));
  }

  @Test
  void claimMismoIntentoEnPagoPendienteDevuelve200SinEfectos() {
    Orden o = guardar("PAGO_PENDIENTE");
    UUID intento = UUID.randomUUID();
    o.setIntentoActualId(intento);
    o.setReservaExpiraEn(Instant.now().plus(30, ChronoUnit.MINUTES));
    ordenes.save(o);
    Instant expira = o.getReservaExpiraEn();

    assertNotNull(service.reclamarPago(o.getId(), USUARIO, intento));
    assertNotNull(service.reclamarPago(o.getId(), USUARIO, intento));

    // Postgres guarda timestamptz con precisión de microsegundos (redondea, no trunca).
    assertTrue(Math.abs(ChronoUnit.MICROS.between(expira, recargar(o.getId()).getReservaExpiraEn())) <= 1);
    assertEquals("PAGO_PENDIENTE", recargar(o.getId()).getEstado());
  }

  @Test
  void claimOtroIntentoEnPagoPendienteDevuelve409() {
    Orden o = guardar("PAGO_PENDIENTE");
    UUID intentoActual = UUID.randomUUID();
    o.setIntentoActualId(intentoActual);
    o.setReservaExpiraEn(Instant.now().plus(30, ChronoUnit.MINUTES));
    ordenes.save(o);

    assertNull(service.reclamarPago(o.getId(), USUARIO, UUID.randomUUID()));
    assertEquals(intentoActual, recargar(o.getId()).getIntentoActualId());
  }

  @Test
  void claimsConcurrentesMismoUuidUnoGanaOtroRelee() {
    Orden o = guardar("RESERVADA");
    o.setReservaExpiraEn(Instant.now().plus(30, ChronoUnit.MINUTES));
    ordenes.save(o);
    UUID intento = UUID.randomUUID();

    assertNotNull(service.reclamarPago(o.getId(), USUARIO, intento));
    assertNotNull(service.reclamarPago(o.getId(), USUARIO, intento)); // re-lectura tras 0 filas
    assertNull(service.reclamarPago(o.getId(), USUARIO, UUID.randomUUID()));
  }

  @Test
  void expiracionGanaClaimSinLlamarProveedor() {
    Orden o = guardar("RESERVADA");
    o.setReservaExpiraEn(Instant.now().minus(1, ChronoUnit.MINUTES));
    ordenes.save(o);

    service.expirarReservas();

    assertEquals("EXPIRADA", recargar(o.getId()).getEstado());
    assertNull(service.reclamarPago(o.getId(), USUARIO, UUID.randomUUID()));
    assertEquals(1, outbox.count());
    OrdenOutbox salida = outbox.findAll().get(0);
    assertNotNull(salida.getPayload());
    assertTrue(salida.getPayload().contains("EXPIRADA"));
  }

  @Test
  void rechazadoViejoDeOtroIntentoNoDesbloqueaElActual() {
    Orden o = guardar("PAGO_PENDIENTE");
    UUID actual = UUID.randomUUID();
    o.setIntentoActualId(actual);
    ordenes.save(o);
    UUID viejo = UUID.randomUUID();

    service.pagoResuelto(pago(o.getId(), viejo, "RECHAZADO"));

    assertEquals("PAGO_PENDIENTE", recargar(o.getId()).getEstado());
    assertEquals(actual, recargar(o.getId()).getIntentoActualId());
    assertTrue(procesados.existsById(new ProcesadoId("PAGO", viejo)));
  }

  @Test
  void aprobadoDeOtroIntentoMarcaRevisionSinLiberar() {
    Orden o = guardar("PAGO_PENDIENTE");
    UUID actual = UUID.randomUUID();
    o.setIntentoActualId(actual);
    ordenes.save(o);
    UUID otro = UUID.randomUUID();

    service.pagoResuelto(pago(o.getId(), otro, "APROBADO"));

    assertTrue(recargar(o.getId()).isRequiereRevision());
    assertEquals("PAGO_PENDIENTE", recargar(o.getId()).getEstado());
    assertEquals(0, outbox.count());
  }

  @Test
  void aprobacionConcurrenteConExpiracionGanaAprobacion() {
    Orden o = guardar("RESERVADA");
    o.setReservaExpiraEn(Instant.now().plus(30, ChronoUnit.MINUTES));
    ordenes.save(o);
    UUID intento = UUID.randomUUID();
    assertNotNull(service.reclamarPago(o.getId(), USUARIO, intento));

    service.expirarReservas(); // PAGO_PENDIENTE no es tocado
    service.pagoResuelto(pago(o.getId(), intento, "APROBADO"));

    assertEquals("PAGO_APROBADO", recargar(o.getId()).getEstado());
    assertEquals(0, outbox.count());
  }

  @Test
  void pagoResueltoRechazadoVerificadoVuelveAReservadaConUnaExtension() {
    Orden o = guardar("PAGO_PENDIENTE");
    UUID intento = UUID.randomUUID();
    Instant expira = Instant.now().plus(1, ChronoUnit.MINUTES);
    o.setIntentoActualId(intento);
    o.setReservaExpiraEn(expira);
    ordenes.save(o);

    service.pagoResuelto(pago(o.getId(), intento, "RECHAZADO"));

    Orden actual = recargar(o.getId());
    assertEquals("RESERVADA", actual.getEstado());
    assertNull(actual.getIntentoActualId());
    assertEquals(1, actual.getReintentosPago());
    assertTrue(actual.getReservaExpiraEn().isAfter(expira));
  }

  @Test
  void cancelarPagoPendienteConBodySinEfecto409() {
    Orden o = guardar("PAGO_PENDIENTE");
    o.setIntentoActualId(UUID.randomUUID());
    ordenes.save(o);

    assertEquals(ResultadoCancelacion.PAGO_PENDIENTE, service.cancelar(o.getId(), USUARIO));

    assertEquals("PAGO_PENDIENTE", recargar(o.getId()).getEstado());
    assertEquals(0, outbox.count());
  }

  @Test
  void cancelarRegistradaConStockTardioLiberaUnaSolaVez() {
    Orden o = guardar("REGISTRADA");

    assertEquals(ResultadoCancelacion.CANCELADA, service.cancelar(o.getId(), USUARIO));
    assertEquals(0, outbox.count()); // cancelar no inventa stock

    service.stockReservado(reservado(o.getId(), UUID.randomUUID()));
    service.stockReservado(reservado(o.getId(), UUID.randomUUID()));

    assertEquals("CANCELADA", recargar(o.getId()).getEstado());
    assertEquals(1, outbox.count());
    OrdenOutbox salida = outbox.findAll().get(0);
    assertTrue(salida.getPayload().contains("CANCELADA"));
  }

  @Test
  void dobleLiberacionNoRestauraStockDosVeces() {
    Orden o = guardar("RESERVADA");
    o.setReservaExpiraEn(Instant.now().minus(1, ChronoUnit.MINUTES));
    ordenes.save(o);

    service.expirarReservas();
    service.expirarReservas();

    assertEquals("EXPIRADA", recargar(o.getId()).getEstado());
    assertEquals(1, outbox.count());
  }

  @Test
  void stockTardioEnPagoPendienteNuncaLibera() {
    Orden o = guardar("PAGO_PENDIENTE");
    o.setIntentoActualId(UUID.randomUUID());
    ordenes.save(o);
    UUID eventoId = UUID.randomUUID();

    service.stockReservado(reservado(o.getId(), eventoId));
    service.stockReservado(reservado(o.getId(), eventoId));

    assertTrue(recargar(o.getId()).isRequiereRevision());
    assertEquals("PAGO_PENDIENTE", recargar(o.getId()).getEstado());
    assertEquals(0, outbox.count());
  }

  @Test
  void reinicioConCompensacionPendienteReenviaMismoEventoId() {
    Orden o = guardar("CANCELADA");
    service.stockReservado(reservado(o.getId(), UUID.randomUUID()));
    OrdenOutbox pendiente = outbox.findAll().get(0);

    // Simula reinicio: la fila sigue pendiente y se reenvía con el mismo id.
    outbox.marcarPendiente(pendiente.getEventoId());
    service.stockReservado(reservado(o.getId(), UUID.randomUUID()));

    assertEquals(1, outbox.count());
    assertEquals(pendiente.getEventoId(), outbox.findAll().get(0).getEventoId());
    assertNull(outbox.findAll().get(0).getPublicadoEn());
  }

  @Test
  void ordenHistoricaSinMetadatosNoPagableNiLiberaStock() {
    Orden o = guardar("REGISTRADA");
    o.setReservaSolicitada(false);
    ordenes.save(o);

    assertEquals(ResultadoCancelacion.CANCELADA, service.cancelar(o.getId(), USUARIO));
    assertNull(service.reclamarPago(o.getId(), USUARIO, UUID.randomUUID()));

    assertEquals("CANCELADA", recargar(o.getId()).getEstado());
    assertEquals(0, outbox.count());
    assertFalse(recargar(o.getId()).getReservaSolicitada());
  }
}
