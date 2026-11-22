package com.pedidos360.orders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.orders.controller.OrdenController;
import com.pedidos360.orders.config.IdentityConfig;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrdenController.class)
@Import(IdentityConfig.class)
class OrdenControllerTest {

  @Autowired
  MockMvc mvc;

  @MockBean
  OrdenRepository ordenRepository;

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
        .andExpect(jsonPath("$.totalClp").value(39980));
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
}
