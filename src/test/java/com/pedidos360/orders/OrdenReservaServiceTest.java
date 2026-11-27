package com.pedidos360.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.config.RabbitMQConfig;
import com.pedidos360.orders.messaging.OrdenCreadaEvent;
import com.pedidos360.orders.messaging.OrdenRegistradaEvent;
import com.pedidos360.orders.messaging.OrdenTopologia;
import com.pedidos360.orders.messaging.PagoResueltoEvent;
import com.pedidos360.orders.messaging.StockRechazadoEvent;
import com.pedidos360.orders.messaging.StockReservadoEvent;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenOutbox;
import com.pedidos360.orders.orden.OrdenOutboxRepository;
import com.pedidos360.orders.orden.OrdenRepository;
import com.pedidos360.orders.orden.OrdenReservaService;
import com.pedidos360.orders.orden.OrdenReservaService.ResultadoCancelacion;
import com.pedidos360.orders.orden.Procesado;
import com.pedidos360.orders.orden.ProcesadoId;
import com.pedidos360.orders.orden.ProcesadoRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/** Reglas G0 sin broker ni BD: stubs de repositorio y ObjectMapper real. */
@ExtendWith(MockitoExtension.class)
class OrdenReservaServiceTest {

  private static final String USUARIO = "usuario-1";

  @Mock OrdenRepository ordenRepository;
  @Mock OrdenOutboxRepository outboxRepository;
  @Mock ProcesadoRepository procesadoRepository;

  OrdenReservaService service;

  @BeforeEach
  void setUp() {
    service = new OrdenReservaService(ordenRepository, outboxRepository, procesadoRepository,
        new ObjectMapper().findAndRegisterModules());
  }

  private Orden orden(String estado) {
    Orden o = new Orden(UUID.randomUUID(), USUARIO, "comprador@mail.com", 19990L, "[]");
    o.setEstado(estado);
    return o;
  }

  private OrdenCreadaEvent creada(UUID id) {
    return new OrdenCreadaEvent(id, USUARIO, "comprador@mail.com",
        List.of(new OrdenCreadaEvent.Item(UUID.randomUUID(), 1, 19990L)), 19990L, null);
  }

  private OrdenRegistradaEvent registrada(UUID id) {
    return new OrdenRegistradaEvent(id, USUARIO, "comprador@mail.com",
        List.of(new OrdenRegistradaEvent.Item(UUID.randomUUID(), 1)), null);
  }

  private StockReservadoEvent reservado(UUID ordenId, UUID eventoId, Instant ocurrioEn) {
    return new StockReservadoEvent(eventoId, ordenId, USUARIO, ocurrioEn);
  }

  private PagoResueltoEvent pago(UUID ordenId, UUID intentoId, String resultado) {
    return new PagoResueltoEvent(UUID.randomUUID(), intentoId, ordenId, USUARIO, resultado, Instant.now());
  }

  /** Deduplicador en memoria con la misma semántica que la PK (tipo, referenciaId). */
  private void dedupProcesados() {
    Set<ProcesadoId> vistos = new HashSet<>();
    when(procesadoRepository.existsById(any())).thenAnswer(inv -> vistos.contains(inv.getArgument(0)));
    doAnswer(inv -> {
      vistos.add(((Procesado) inv.getArgument(0)).getId());
      return null;
    }).when(procesadoRepository).save(any());
  }

  @Test
  void registrarNuevo_guardaOrdenYSalidaPendienteConEventoIdFijo() {
    UUID id = UUID.randomUUID();
    when(ordenRepository.existsById(id)).thenReturn(false);

    service.registrar(creada(id), registrada(id));

    ArgumentCaptor<OrdenOutbox> salida = ArgumentCaptor.forClass(OrdenOutbox.class);
    verify(outboxRepository).save(salida.capture());
    UUID esperado = UUID.nameUUIDFromBytes(("orden.registrada:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(esperado, salida.getValue().getEventoId());
    assertEquals(RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY, salida.getValue().getRoutingKey());
    assertTrue(salida.getValue().getPayload().contains("comprador@mail.com"));
    verify(ordenRepository).save(any(Orden.class));
  }

  @Test
  void registrarDuplicadoConSalidaPendiente_noCreaOtraFila() {
    UUID id = UUID.randomUUID();
    UUID eventoId = UUID.nameUUIDFromBytes(("orden.registrada:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(ordenRepository.existsById(id)).thenReturn(true);
    when(outboxRepository.findById(eventoId)).thenReturn(Optional.of(
        new OrdenOutbox(eventoId, RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY, "{}", Instant.now())));

    service.registrar(creada(id), registrada(id));

    verify(outboxRepository, never()).save(any());
    verify(outboxRepository, never()).marcarPendiente(any());
  }

  @Test
  void registrarDuplicadoConSalidaYaEnviada_reproduceMismoEventoId() {
    UUID id = UUID.randomUUID();
    UUID eventoId = UUID.nameUUIDFromBytes(("orden.registrada:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(ordenRepository.existsById(id)).thenReturn(true);
    OrdenOutbox enviada = new OrdenOutbox(eventoId, RabbitMQConfig.ORDEN_REGISTRADA_ROUTING_KEY, "{}", Instant.now());
    org.springframework.test.util.ReflectionTestUtils.setField(enviada, "estado", "ENVIADO");
    when(outboxRepository.findById(eventoId)).thenReturn(Optional.of(enviada));

    service.registrar(creada(id), registrada(id));

    verify(outboxRepository).marcarPendiente(eventoId);
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void stockReservadoDuplicadoRepublicaSinCambiarEstado() {
    UUID ordenId = UUID.randomUUID();
    UUID eventoId = UUID.randomUUID();
    when(procesadoRepository.existsById(new ProcesadoId("STOCK", eventoId))).thenReturn(true);

    service.stockReservado(reservado(ordenId, eventoId, Instant.now()));

    verify(ordenRepository, never()).findById(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void stockReservadoEnRegistrada_fijaRelojYProcesado() {
    UUID ordenId = UUID.randomUUID();
    Orden o = orden("REGISTRADA");
    Instant ocurrioEn = Instant.parse("2026-11-27T10:00:00Z");
    dedupProcesados();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));

    service.stockReservado(reservado(ordenId, UUID.randomUUID(), ocurrioEn));

    ArgumentCaptor<Orden> captor = ArgumentCaptor.forClass(Orden.class);
    verify(ordenRepository).save(captor.capture());
    assertEquals("RESERVADA", captor.getValue().getEstado());
    assertEquals(ocurrioEn.plus(30, ChronoUnit.MINUTES), captor.getValue().getReservaExpiraEn());
  }

  @Test
  void rechazoStockDurableHaceOrdenNoPagable() {
    UUID ordenId = UUID.randomUUID();
    Orden o = orden("REGISTRADA");
    dedupProcesados();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));

    service.stockRechazado(new StockRechazadoEvent(UUID.randomUUID(), ordenId, USUARIO,
        "SIN_STOCK", Instant.now()));

    ArgumentCaptor<Orden> captor = ArgumentCaptor.forClass(Orden.class);
    verify(ordenRepository).save(captor.capture());
    assertEquals("SIN_STOCK", captor.getValue().getEstado());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void claimMismoIntentoEnPagoPendienteDevuelve200SinEfectos() {
    UUID ordenId = UUID.randomUUID();
    UUID intentoId = UUID.randomUUID();
    when(ordenRepository.findByIdAndUsuarioIdAndEstadoAndIntentoActualId(
        ordenId, USUARIO, "PAGO_PENDIENTE", intentoId)).thenReturn(Optional.of(orden("PAGO_PENDIENTE")));

    assertNotNull(service.reclamarPago(ordenId, USUARIO, intentoId));

    verify(ordenRepository, never()).reclamar(any(), any(), any(), any());
    verify(ordenRepository, never()).save(any());
  }

  @Test
  void claimOtroIntentoEnPagoPendienteDevuelve409() {
    UUID ordenId = UUID.randomUUID();
    UUID intentoA = UUID.randomUUID();
    UUID intentoB = UUID.randomUUID();
    Orden o = orden("PAGO_PENDIENTE");
    o.setIntentoActualId(intentoA);
    when(ordenRepository.findByIdAndUsuarioIdAndEstadoAndIntentoActualId(
        ordenId, USUARIO, "PAGO_PENDIENTE", intentoB)).thenReturn(Optional.empty());
    when(ordenRepository.reclamar(any(), any(), any(), any())).thenReturn(0);
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));

    assertNull(service.reclamarPago(ordenId, USUARIO, intentoB));
  }

  @Test
  void claimsConcurrentesMismoUuidUnoGanaOtroRelee() {
    UUID ordenId = UUID.randomUUID();
    UUID intentoId = UUID.randomUUID();
    Orden o = orden("PAGO_PENDIENTE");
    o.setIntentoActualId(intentoId);
    when(ordenRepository.findByIdAndUsuarioIdAndEstadoAndIntentoActualId(
        ordenId, USUARIO, "PAGO_PENDIENTE", intentoId)).thenReturn(Optional.empty());
    when(ordenRepository.reclamar(any(), any(), any(), any())).thenReturn(0);
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));

    assertNotNull(service.reclamarPago(ordenId, USUARIO, intentoId));
  }

  @Test
  void expiracionGanaClaimSinLlamarProveedor() {
    UUID ordenId = UUID.randomUUID();
    when(ordenRepository.findByIdAndUsuarioIdAndEstadoAndIntentoActualId(
        any(), any(), any(), any())).thenReturn(Optional.empty());
    when(ordenRepository.reclamar(any(), any(), any(), any())).thenReturn(0);
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(orden("EXPIRADA")));

    assertNull(service.reclamarPago(ordenId, USUARIO, UUID.randomUUID()));
    verify(ordenRepository, never()).save(any());
  }

  @Test
  void rechazadoViejoDeOtroIntentoNoDesbloqueaElActual() {
    UUID ordenId = UUID.randomUUID();
    UUID intentoA = UUID.randomUUID();
    Orden o = orden("PAGO_PENDIENTE");
    o.setIntentoActualId(UUID.randomUUID());
    dedupProcesados();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));

    service.pagoResuelto(pago(ordenId, intentoA, "RECHAZADO"));

    verify(ordenRepository, never()).save(any());
    verify(ordenRepository, never()).resolverPago(any(), any(), any(), any(), any());
    assertEquals("PAGO_PENDIENTE", o.getEstado());
  }

  @Test
  void aprobadoDeOtroIntentoMarcaRevisionSinLiberar() {
    UUID ordenId = UUID.randomUUID();
    Orden o = orden("PAGO_PENDIENTE");
    o.setIntentoActualId(UUID.randomUUID());
    dedupProcesados();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));

    service.pagoResuelto(pago(ordenId, UUID.randomUUID(), "APROBADO"));

    ArgumentCaptor<Orden> captor = ArgumentCaptor.forClass(Orden.class);
    verify(ordenRepository).save(captor.capture());
    assertTrue(captor.getValue().isRequiereRevision());
    assertEquals("PAGO_PENDIENTE", captor.getValue().getEstado());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void pagoResueltoRechazadoVerificadoVuelveAReservadaConUnaExtension() {
    UUID intentoId = UUID.randomUUID();
    Orden o = orden("PAGO_PENDIENTE");
    UUID ordenId = o.getId();
    o.setIntentoActualId(intentoId);
    dedupProcesados();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));
    when(ordenRepository.resolverPago(any(), any(), any(), any(), any())).thenReturn(1);

    service.pagoResuelto(pago(ordenId, intentoId, "RECHAZADO"));

    verify(ordenRepository).resolverPago(eq(ordenId), eq(intentoId), eq("RECHAZADO"), eq("RESERVADA"), any());
    verify(procesadoRepository).save(any(Procesado.class));
  }

  @Test
  void aprobacionConcurrenteConExpiracionGanaAprobacion() {
    UUID intentoId = UUID.randomUUID();
    Orden o = orden("PAGO_PENDIENTE");
    UUID ordenId = o.getId();
    o.setIntentoActualId(intentoId);
    Orden aprobada = orden("PAGO_APROBADO");
    aprobada.setIntentoActualId(intentoId);
    dedupProcesados();
    // Primera lectura ve PENDIENTE; el CAS pierde y la re-lectura ya ve APROBADO.
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o), Optional.of(aprobada));
    when(ordenRepository.resolverPago(any(), any(), any(), any(), any())).thenReturn(0);

    service.pagoResuelto(pago(ordenId, intentoId, "APROBADO"));

    ArgumentCaptor<Procesado> captor = ArgumentCaptor.forClass(Procesado.class);
    verify(procesadoRepository).save(captor.capture());
    assertEquals("APROBADO", captor.getValue().getResultado());
    assertEquals("PAGO_PENDIENTE", o.getEstado()); // no se fuerza ningún cambio
  }

  @Test
  void cancelarPagoPendienteConBodySinEfecto409() {
    UUID ordenId = UUID.randomUUID();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(orden("PAGO_PENDIENTE")));

    assertEquals(ResultadoCancelacion.PAGO_PENDIENTE, service.cancelar(ordenId, USUARIO));

    verify(ordenRepository, never()).cancelarReservada(any());
    verify(ordenRepository, never()).cancelarSinReserva(any(), any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void cancelarRegistradaSinFilasReleeYNoAfirmaCancelacion() {
    Orden o = orden("REGISTRADA");
    UUID ordenId = o.getId();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));
    when(ordenRepository.cancelarSinReserva(ordenId, USUARIO)).thenReturn(0);

    assertEquals(ResultadoCancelacion.NO_CANCELADA, service.cancelar(ordenId, USUARIO));
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void cancelarSinReservaYaCanceladaDevuelve200Idempotente() {
    Orden previa = orden("REGISTRADA");
    UUID ordenId = previa.getId();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(previa),
        Optional.of(orden("CANCELADA")));
    when(ordenRepository.cancelarSinReserva(ordenId, USUARIO)).thenReturn(0);

    assertEquals(ResultadoCancelacion.CANCELADA, service.cancelar(ordenId, USUARIO));
  }

  @Test
  void cancelarSinReservaConPagoVigenteDevuelve409() {
    Orden previa = orden("REGISTRADA");
    UUID ordenId = previa.getId();
    Orden enPago = orden("PAGO_PENDIENTE");
    enPago.setIntentoActualId(UUID.randomUUID());
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(previa), Optional.of(enPago));
    when(ordenRepository.cancelarSinReserva(ordenId, USUARIO)).thenReturn(0);

    assertEquals(ResultadoCancelacion.PAGO_PENDIENTE, service.cancelar(ordenId, USUARIO));
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void cancelarReservadaSinFilasReleeYNoAfirmaCancelacion() {
    Orden o = orden("RESERVADA");
    UUID ordenId = o.getId();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));
    when(ordenRepository.cancelarReservada(ordenId)).thenReturn(0);

    assertEquals(ResultadoCancelacion.NO_CANCELADA, service.cancelar(ordenId, USUARIO));
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void cancelarRegistradaConStockTardioLiberaUnaSolaVez() {
    UUID ordenId = UUID.randomUUID();
    Orden o = orden("REGISTRADA");
    dedupProcesados();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));
    doAnswer(inv -> {
      o.setEstado("CANCELADA");
      return 1;
    }).when(ordenRepository).cancelarSinReserva(ordenId, USUARIO);

    assertEquals(ResultadoCancelacion.CANCELADA, service.cancelar(ordenId, USUARIO));
    verify(outboxRepository, never()).save(any()); // cancelar REGISTRADA no inventa stock

    service.stockReservado(reservado(ordenId, UUID.randomUUID(), Instant.now()));
    service.stockReservado(reservado(ordenId, UUID.randomUUID(), Instant.now()));

    ArgumentCaptor<OrdenOutbox> salidas = ArgumentCaptor.forClass(OrdenOutbox.class);
    verify(outboxRepository).save(salidas.capture());
    assertEquals(OrdenTopologia.STOCK_LIBERADO_ROUTING_KEY, salidas.getValue().getRoutingKey());
    assertTrue(salidas.getValue().getPayload().contains("CANCELADA"));
  }

  @Test
  void dobleLiberacionNoRestauraStockDosVeces() {
    Orden o = orden("RESERVADA");
    UUID ordenId = o.getId();
    o.setReservaExpiraEn(Instant.now().minus(1, ChronoUnit.MINUTES));
    dedupProcesados();
    when(ordenRepository.findByEstadoAndReservaExpiraEnBefore(any(), any(), any(Pageable.class)))
        .thenReturn(List.of(o));
    when(ordenRepository.expirar(eq(ordenId), any())).thenReturn(1);

    service.expirarReservas();
    service.expirarReservas();

    verify(outboxRepository).save(any(OrdenOutbox.class));
  }

  @Test
  void stockTardioEnPagoPendienteNuncaLibera() {
    UUID ordenId = UUID.randomUUID();
    Orden o = orden("PAGO_PENDIENTE");
    o.setIntentoActualId(UUID.randomUUID());
    dedupProcesados();
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));

    service.stockReservado(reservado(ordenId, UUID.randomUUID(), Instant.now()));

    ArgumentCaptor<Orden> captor = ArgumentCaptor.forClass(Orden.class);
    verify(ordenRepository).save(captor.capture());
    assertTrue(captor.getValue().isRequiereRevision());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void ordenHistoricaSinMetadatosNoPagableNiLiberaStock() {
    UUID ordenId = UUID.randomUUID();
    Orden o = orden("REGISTRADA");
    o.setReservaSolicitada(false);
    when(ordenRepository.findById(ordenId)).thenReturn(Optional.of(o));
    when(ordenRepository.cancelarSinReserva(ordenId, USUARIO)).thenReturn(1);
    when(ordenRepository.reclamar(any(), any(), any(), any())).thenReturn(0);

    assertEquals(ResultadoCancelacion.CANCELADA, service.cancelar(ordenId, USUARIO));
    assertNull(service.reclamarPago(ordenId, USUARIO, UUID.randomUUID()));
    assertFalse(o.getReservaSolicitada());
    verify(outboxRepository, never()).save(any());
  }
}
