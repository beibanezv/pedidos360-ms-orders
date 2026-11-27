package com.pedidos360.orders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.orders.config.IdentityConfig;
import com.pedidos360.orders.controller.OrdenController;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import com.pedidos360.orders.orden.OrdenReservaService;
import com.pedidos360.orders.orden.OrdenReservaService.ResultadoCancelacion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrdenController.class)
@Import(IdentityConfig.class)
class OrdenControllerTest {

  @Autowired
  MockMvc mvc;

  @MockBean
  OrdenRepository ordenRepository;

  @MockBean
  OrdenReservaService service;

  @Test
  void ordenExistenteSinTokenRetorna401() throws Exception {
    Orden orden = new Orden(UUID.randomUUID(), "usuario-1", "comprador@mail.com", 39980L, "[]");
    when(ordenRepository.findById(orden.getId())).thenReturn(Optional.of(orden));

    mvc.perform(get("/ordenes/" + orden.getId()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokenMalformadoRetorna401() throws Exception {
    mvc.perform(get("/ordenes").header("Authorization", "Bearer a.b.c"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void porId_existe_retorna200() throws Exception {
    Orden orden = new Orden(UUID.randomUUID(), "usuario-1", "comprador@mail.com", 39980L, "[]");
    when(ordenRepository.findById(orden.getId())).thenReturn(Optional.of(orden));

    mvc.perform(get("/ordenes/" + orden.getId()).with(jwt().jwt(j -> j.subject("usuario-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ordenId").value(orden.getId().toString()))
        .andExpect(jsonPath("$.estado").value("REGISTRADA"))
        .andExpect(jsonPath("$.itemsJson").value("[]"))
        .andExpect(jsonPath("$.totalClp").value(39980))
        .andExpect(jsonPath("$.requiereRevision").value(false));
  }

  @Test
  void porId_falta_retorna404() throws Exception {
    when(ordenRepository.findById(any())).thenReturn(Optional.empty());

    mvc.perform(get("/ordenes/" + UUID.randomUUID()).with(jwt().jwt(j -> j.subject("usuario-1"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void ordenAjenaRetorna404() throws Exception {
    Orden orden = new Orden(UUID.randomUUID(), "usuario-1", "comprador@mail.com", 39980L, "[]");
    when(ordenRepository.findById(orden.getId())).thenReturn(Optional.of(orden));

    mvc.perform(get("/ordenes/" + orden.getId()).with(jwt().jwt(j -> j.subject("intruso"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void porUsuario_retornaLista() throws Exception {
    Orden orden = new Orden(UUID.randomUUID(), "usuario-1", "comprador@mail.com", 19990L, "[]");
    when(ordenRepository.findByUsuarioIdOrderByCreatedAtDesc("usuario-1"))
        .thenReturn(List.of(orden));

    mvc.perform(get("/ordenes").param("usuarioId", "otro-usuario")
            .with(jwt().jwt(j -> j.subject("sub-distinto").claim("oid", "usuario-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].ordenId").value(orden.getId().toString()));
  }

  @Test
  void respuestaIncluyeMetadatosDeReserva() throws Exception {
    Orden orden = new Orden(UUID.randomUUID(), "usuario-1", "comprador@mail.com", 39980L, "[]");
    orden.setEstado("PAGO_PENDIENTE");
    orden.setReservaExpiraEn(java.time.Instant.parse("2026-11-27T12:00:00Z"));
    orden.setIntentoActualId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    orden.setRequiereRevision(true);
    when(ordenRepository.findById(orden.getId())).thenReturn(Optional.of(orden));

    mvc.perform(get("/ordenes/" + orden.getId()).with(jwt().jwt(j -> j.subject("usuario-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("PAGO_PENDIENTE"))
        .andExpect(jsonPath("$.reservaExpiraEn").value("2026-11-27T12:00:00Z"))
        .andExpect(jsonPath("$.intentoActualId").value("11111111-1111-4111-8111-111111111111"))
        .andExpect(jsonPath("$.requiereRevision").value(true));
  }

  @Test
  void claimMismoIntentoDevuelve200() throws Exception {
    UUID ordenId = UUID.randomUUID();
    UUID intentoId = UUID.randomUUID();
    when(service.reclamarPago(ordenId, "usuario-1", intentoId)).thenReturn(true);

    mvc.perform(post("/ordenes/" + ordenId + "/pago/claim")
            .with(jwt().jwt(j -> j.subject("usuario-1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"intentoId\":\"" + intentoId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("PAGO_PENDIENTE"));
  }

  @Test
  void claimOtroIntentoDevuelve409() throws Exception {
    UUID ordenId = UUID.randomUUID();
    UUID intentoId = UUID.randomUUID();
    when(service.reclamarPago(eq(ordenId), eq("usuario-1"), any())).thenReturn(false);

    mvc.perform(post("/ordenes/" + ordenId + "/pago/claim")
            .with(jwt().jwt(j -> j.subject("usuario-1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"intentoId\":\"" + intentoId + "\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void cancelarPagoPendienteDevuelve409() throws Exception {
    UUID ordenId = UUID.randomUUID();
    when(service.cancelar(ordenId, "usuario-1")).thenReturn(ResultadoCancelacion.PAGO_PENDIENTE);

    mvc.perform(post("/ordenes/" + ordenId + "/cancelar")
            .with(jwt().jwt(j -> j.subject("usuario-1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"CANCELA\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void cancelarSinMotivoNiDesistenciaDevuelve400() throws Exception {
    UUID ordenId = UUID.randomUUID();

    mvc.perform(post("/ordenes/" + ordenId + "/cancelar")
            .with(jwt().jwt(j -> j.subject("usuario-1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"intentoId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cancelarRegistradaDevuelve200() throws Exception {
    UUID ordenId = UUID.randomUUID();
    when(service.cancelar(ordenId, "usuario-1")).thenReturn(ResultadoCancelacion.CANCELADA);

    mvc.perform(post("/ordenes/" + ordenId + "/cancelar")
            .with(jwt().jwt(j -> j.subject("usuario-1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"CANCELA\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("CANCELADA"));
  }
}
