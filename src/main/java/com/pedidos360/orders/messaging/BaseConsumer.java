package com.pedidos360.orders.messaging;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Message;

/**
 * Plantilla de ACK manual copiada de ms-productos (sin libs compartidas:
 * cada repo es independiente). Mismo contrato: IllegalArgumentException =
 * error PERMANENTE (→ DLQ directo); otra RuntimeException = TRANSITORIO
 * (requeue hasta maxReintentos() muertes en x-death, después DLQ).
 */
public abstract class BaseConsumer {

  /** Reintentos de errores transitorios antes de mandar a la DLQ. */
  protected int maxReintentos() {
    return 3;
  }

  protected void consumir(Channel channel, long tag, Message raw, Accion accion)
      throws IOException {
    try {
      accion.ejecutar();
      channel.basicAck(tag, false);
    } catch (IllegalArgumentException e) {
      // Permanente: a la DLQ sin gastar reintentos.
      channel.basicNack(tag, false, false);
    } catch (RuntimeException e) {
      if (muertes(raw) >= maxReintentos()) {
        channel.basicNack(tag, false, false);
      } else {
        channel.basicNack(tag, false, true);
      }
    }
  }

  /** Cuántas veces ya murió este mensaje (cabecera x-death que pone el DLX). */
  static long muertes(Message raw) {
    List<? extends Map<String, ?>> xDeath = raw.getMessageProperties().getXDeathHeader();
    if (xDeath == null || xDeath.isEmpty()) return 0;
    Object count = xDeath.get(0).get("count");
    return count instanceof Number n ? n.longValue() : 0;
  }

  @FunctionalInterface
  protected interface Accion {
    void ejecutar();
  }
}
