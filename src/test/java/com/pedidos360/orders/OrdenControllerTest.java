package com.pedidos360.orders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.orders.controller.OrdenController;
import com.pedidos360.orders.orden.Orden;
import com.pedidos360.orders.orden.OrdenRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrdenController.class)
class OrdenControllerTest {

  @Autowired
  MockMvc mvc;

  @MockBean
  OrdenRepository ordenRepository;

  @Test
  void porId_existe_retorna200() throws Exception {
    Orden orden = new Orden(UUID.randomUUID(), "usuario-1", 39980L, "[]");
    when(ordenRepository.findById(orden.getId())).thenReturn(Optional.of(orden));

    mvc.perform(get("/ordenes/" + orden.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ordenId").value(orden.getId().toString()))
        .andExpect(jsonPath("$.estado").value("REGISTRADA"))
        .andExpect(jsonPath("$.itemsJson").value("[]"))
        .andExpect(jsonPath("$.totalClp").value(39980));
  }

  @Test
  void porId_falta_retorna404() throws Exception {
    when(ordenRepository.findById(any())).thenReturn(Optional.empty());

    mvc.perform(get("/ordenes/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void porUsuario_retornaLista() throws Exception {
    Orden orden = new Orden(UUID.randomUUID(), "usuario-1", 19990L, "[]");
    when(ordenRepository.findByUsuarioIdOrderByCreatedAtDesc("usuario-1"))
        .thenReturn(List.of(orden));

    mvc.perform(get("/ordenes").param("usuarioId", "usuario-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].ordenId").value(orden.getId().toString()));
  }
}
