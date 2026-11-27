package com.pedidos360.orders.orden;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** PK de procesados: (tipo, referenciaId) = (PAGO,intentoId) o (STOCK,eventoId)/(LIBERADO,ordenId). */
@Embeddable
public class ProcesadoId implements Serializable {

  private String tipo;
  private UUID referenciaId;

  protected ProcesadoId() {}

  public ProcesadoId(String tipo, UUID referenciaId) {
    this.tipo = tipo;
    this.referenciaId = referenciaId;
  }

  public String getTipo() { return tipo; }
  public UUID getReferenciaId() { return referenciaId; }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof ProcesadoId o)) return false;
    return java.util.Objects.equals(tipo, o.tipo)
        && java.util.Objects.equals(referenciaId, o.referenciaId);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(tipo, referenciaId);
  }
}
