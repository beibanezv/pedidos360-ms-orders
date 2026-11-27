package com.pedidos360.orders.orden;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Constancia de un evento ya aplicado. Se inserta en la MISMA transacción que
 * el cambio de estado: si ya existe, el redelivery solo hace ACK.
 * ordenId (columna auxiliar) permite la comprobación local "sin pago aprobado"
 * antes de compensar un stock tardío.
 */
@Entity
@Table(name = "procesados")
public class Procesado {

  @EmbeddedId
  private ProcesadoId id;

  private UUID ordenId;
  private String resultado;

  @Column(nullable = false)
  private Instant ocurrioEn = Instant.now();

  protected Procesado() {}

  public Procesado(ProcesadoId id, UUID ordenId, String resultado, Instant ocurrioEn) {
    this.id = id;
    this.ordenId = ordenId;
    this.resultado = resultado;
    this.ocurrioEn = ocurrioEn;
  }

  public ProcesadoId getId() { return id; }
  public UUID getOrdenId() { return ordenId; }
  public String getResultado() { return resultado; }
  public Instant getOcurrioEn() { return ocurrioEn; }
}
