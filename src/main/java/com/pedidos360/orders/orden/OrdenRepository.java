package com.pedidos360.orders.orden;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OrdenRepository extends JpaRepository<Orden, UUID> {

  List<Orden> findByUsuarioIdOrderByCreatedAtDesc(String usuarioId);

  List<Orden> findByEstadoAndReservaExpiraEnBefore(String estado, Instant ahora, Pageable pageable);

  Optional<Orden> findByIdAndUsuarioIdAndEstadoAndIntentoActualId(
      UUID id, String usuarioId, String estado, UUID intentoActualId);

  /** Rama 2 del claim: primer intento, solo desde RESERVADA con reloj vigente. */
  @Modifying
  @Transactional
  @Query("update Orden o set o.estado = 'PAGO_PENDIENTE', o.intentoActualId = :intentoId "
      + "where o.id = :id and o.usuarioId = :usuarioId and o.estado = 'RESERVADA' "
      + "and o.reservaExpiraEn > :ahora and o.intentoActualId is null")
  int reclamar(@Param("id") UUID id, @Param("usuarioId") String usuarioId,
      @Param("intentoId") UUID intentoId, @Param("ahora") Instant ahora);

  /** Solo RESERVADA vencida: PAGO_PENDIENTE nunca es tocada por el expirador. */
  @Modifying
  @Transactional
  @Query("update Orden o set o.estado = 'EXPIRADA' where o.id = :id "
      + "and o.estado = 'RESERVADA' and o.reservaExpiraEn <= :ahora")
  int expirar(@Param("id") UUID id, @Param("ahora") Instant ahora);

  /**
   * CAS del pago resuelto. APROBADO conserva el intento y no incrementa
   * reintentos; RECHAZADO lo limpia, incrementa y extiende el reloj solo una vez
   * (cuando reintentosPago pasa de 0 a 1).
   */
  @Modifying
  @Transactional
  @Query("update Orden o set o.estado = :estadoFinal, "
      + "o.intentoActualId = case when :resultado = 'RECHAZADO' then null else o.intentoActualId end, "
      + "o.reintentosPago = case when :resultado = 'RECHAZADO' then o.reintentosPago + 1 else o.reintentosPago end, "
      + "o.reservaExpiraEn = case when :resultado = 'RECHAZADO' and o.reintentosPago = 0 "
      + "then :nuevaExpiracion else o.reservaExpiraEn end "
      + "where o.id = :id and o.intentoActualId = :intentoId and o.estado = 'PAGO_PENDIENTE'")
  int resolverPago(@Param("id") UUID id, @Param("intentoId") UUID intentoId,
      @Param("resultado") String resultado, @Param("estadoFinal") String estadoFinal,
      @Param("nuevaExpiracion") Instant nuevaExpiracion);

  @Modifying
  @Transactional
  @Query("update Orden o set o.estado = 'CANCELADA' where o.id = :id and o.estado = 'RESERVADA'")
  int cancelarReservada(@Param("id") UUID id);

  @Modifying
  @Transactional
  @Query("update Orden o set o.estado = 'CANCELADA' where o.id = :id "
      + "and o.usuarioId = :usuarioId and o.estado in ('REGISTRADA','SIN_STOCK')")
  int cancelarSinReserva(@Param("id") UUID id, @Param("usuarioId") String usuarioId);
}
