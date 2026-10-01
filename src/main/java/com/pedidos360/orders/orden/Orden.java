package com.pedidos360.orders.orden;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Orden registrada formalmente. El id es el ordenId que generó ms-carrito
 * en el checkout: así la idempotencia es gratis (misma orden dos veces =
 * misma PK, el consumidor la detecta con existsById).
 *
 * // SIMPLIFICADO: los ítems van como JSON en una columna TEXT. Upgrade =
 * // tabla orden_items con FK cuando se necesiten consultas por producto.
 */
@Entity
@Table(name = "ordenes")
public class Orden {

  @Id
  private UUID id;

  @Column(nullable = false)
  private String usuarioId;

  // Email del comprador (obligatorio desde el checkout; nullable en BD para
  // no romper filas históricas creadas antes de este campo).
  @Column
  private String emailComprador;

  @Column(nullable = false)
  private long totalClp;

  @Column(nullable = false)
  private String estado = "REGISTRADA";

  @Column(columnDefinition = "TEXT", nullable = false)
  private String itemsJson;

  @Column(nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected Orden() {}

  public Orden(UUID id, String usuarioId, String emailComprador, long totalClp, String itemsJson) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.emailComprador = emailComprador;
    this.totalClp = totalClp;
    this.itemsJson = itemsJson;
  }

  public UUID getId() { return id; }
  public String getUsuarioId() { return usuarioId; }
  public String getEmailComprador() { return emailComprador; }
  public long getTotalClp() { return totalClp; }
  public String getEstado() { return estado; }
  public String getItemsJson() { return itemsJson; }
  public Instant getCreatedAt() { return createdAt; }
}
