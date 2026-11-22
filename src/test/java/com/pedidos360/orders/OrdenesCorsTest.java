package com.pedidos360.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.orders.config.IdentityConfig;
import com.pedidos360.orders.controller.OrdenController;
import com.pedidos360.orders.orden.OrdenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El frontend Angular llama a /ordenes desde otro origen con header Authorization,
 * asi que sin CORS el preflight falla y la vista "Mis ordenes" no carga.
 */
@WebMvcTest(OrdenController.class)
@Import(IdentityConfig.class)
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:4200")
class OrdenesCorsTest {

  @Autowired
  MockMvc mvc;

  @MockBean
  OrdenRepository ordenRepository;

  @Test
  void preflight_desdeFrontend_permitido() throws Exception {
    mvc.perform(options("/ordenes")
            .header("Origin", "http://localhost:4200")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
        // Spring devuelve el Allow-Headers con el mismo casing que pidió el navegador.
        .andExpect(header().string("Access-Control-Allow-Headers", "authorization"));
  }

  @Test
  void get_conOriginDevuelveAllowOrigin() throws Exception {
    mvc.perform(get("/ordenes").with(jwt()).header("Origin", "http://localhost:4200"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
  }

  @Test
  void origenDesconocido_noRecibeAllowOrigin() throws Exception {
    mvc.perform(options("/ordenes")
            .header("Origin", "https://sitio-malicioso.example")
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }
}
