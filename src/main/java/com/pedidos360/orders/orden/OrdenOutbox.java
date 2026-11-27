package com.pedidos360.orders.orden;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Salida pendiente de orders (orden.registrada y stock.liberado). La PK es el
 * eventoId fijo: un reenvío tras caída o un duplicado no crea otra fila.
 */
@Entity
@Table(name = "ordenes_outbox")
public class OrdenOutbox {

  @Id
  private UUID eventoId;

  @Column(nullable = false)
  private String routingKey;

  @Column(columnDefinition = "TEXT", nullable = false)
  private String payload;

  @Column(nullable = false)
  private Instant ocurrioEn;

  @Column(nullable = false)
  private String estado = "PENDIENTE";

  private int intentos;

  @Column(nullable = false)
  private Instant creadaEn = Instant.now();

  /** Solo se rellena tras la confirmación del broker. */
  @Column(name = "publicado_en")
  private Instant publicadoEn;

  protected OrdenOutbox() {}

  public OrdenOutbox(UUID eventoId, String routingKey, String payload, Instant ocurrioEn) {
    this.eventoId = eventoId;
    this.routingKey = routingKey;
    this.payload = payload;
    this.ocurrioEn = ocurrioEn;
  }

  public UUID getEventoId() { return eventoId; }
  public String getRoutingKey() { return routingKey; }
  public String getPayload() { return payload; }
  public Instant getOcurrioEn() { return ocurrioEn; }
  public String getEstado() { return estado; }
  public int getIntentos() { return intentos; }
  public Instant getCreadaEn() { return creadaEn; }
  public Instant getPublicadoEn() { return publicadoEn; }
}
