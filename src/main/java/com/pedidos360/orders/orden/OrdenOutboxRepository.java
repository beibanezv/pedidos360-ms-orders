package com.pedidos360.orders.orden;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OrdenOutboxRepository extends JpaRepository<OrdenOutbox, UUID> {

  List<OrdenOutbox> findByEstadoOrderByCreadaEnAsc(String estado, Pageable pageable);

  @Modifying
  @Transactional
  @Query("update OrdenOutbox o set o.estado = 'ENVIADO', o.publicadoEn = :publicadoEn "
      + "where o.eventoId = :eventoId")
  void marcarPublicado(@Param("eventoId") UUID eventoId, @Param("publicadoEn") Instant publicadoEn);

  /** Duplicado con salida ya publicada: se reproduce sin crear otra fila. */
  @Modifying
  @Transactional
  @Query("update OrdenOutbox o set o.estado = 'PENDIENTE', o.publicadoEn = null where o.eventoId = :eventoId")
  void marcarPendiente(@Param("eventoId") UUID eventoId);

  @Modifying
  @Transactional
  @Query("update OrdenOutbox o set o.intentos = o.intentos + 1 where o.eventoId = :eventoId")
  void incrementarIntentos(@Param("eventoId") UUID eventoId);
}
