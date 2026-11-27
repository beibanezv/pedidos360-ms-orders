package com.pedidos360.orders.orden;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcesadoRepository extends JpaRepository<Procesado, ProcesadoId> {

  @Query("select count(p) > 0 from Procesado p where p.id.tipo = :tipo "
      + "and p.resultado = :resultado and p.ordenId = :ordenId")
  boolean existeAprobado(@Param("tipo") String tipo, @Param("resultado") String resultado,
      @Param("ordenId") UUID ordenId);
}
