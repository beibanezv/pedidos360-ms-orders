package com.pedidos360.orders.orden;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrdenRepository extends JpaRepository<Orden, UUID> {

  List<Orden> findByUsuarioIdOrderByCreatedAtDesc(String usuarioId);
}
