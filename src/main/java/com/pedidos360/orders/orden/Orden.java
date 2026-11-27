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
 * misma PK). Los metadatos de reserva nuevos son nullable en BD para no
 * romper las filas históricas (ddl-auto=update sobre tabla con datos).
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

  // false en históricas; el constructor de órdenes nuevas lo pone en true.
  private boolean reservaSolicitada;
  private Instant reservaExpiraEn;
  private UUID intentoActualId;
  private int reintentosPago;
  private boolean requiereRevision;

  protected Orden() {}

  public Orden(UUID id, String usuarioId, String emailComprador, long totalClp, String itemsJson) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.emailComprador = emailComprador;
    this.totalClp = totalClp;
    this.itemsJson = itemsJson;
    this.reservaSolicitada = true;
  }

  public UUID getId() { return id; }
  public String getUsuarioId() { return usuarioId; }
  public String getEmailComprador() { return emailComprador; }
  public long getTotalClp() { return totalClp; }
  public String getEstado() { return estado; }
  public String getItemsJson() { return itemsJson; }
  public Instant getCreatedAt() { return createdAt; }
  public boolean getReservaSolicitada() { return reservaSolicitada; }
  public Instant getReservaExpiraEn() { return reservaExpiraEn; }
  public UUID getIntentoActualId() { return intentoActualId; }
  public int getReintentosPago() { return reintentosPago; }
  public boolean isRequiereRevision() { return requiereRevision; }

  public void setEstado(String estado) { this.estado = estado; }
  public void setReservaSolicitada(boolean reservaSolicitada) { this.reservaSolicitada = reservaSolicitada; }
  public void setReservaExpiraEn(Instant reservaExpiraEn) { this.reservaExpiraEn = reservaExpiraEn; }
  public void setIntentoActualId(UUID intentoActualId) { this.intentoActualId = intentoActualId; }
  public void setReintentosPago(int reintentosPago) { this.reintentosPago = reintentosPago; }
  public void setRequiereRevision(boolean requiereRevision) { this.requiereRevision = requiereRevision; }
}
