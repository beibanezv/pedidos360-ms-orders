# pedidos360-ms-orders

Microservicio de órdenes de Pedidos360 (EP3/EP4 — DSY1107).

## Qué hace

1. Consume `orden.creada` de `p360.ordenes.queue` (la publica ms-carrito al hacer checkout).
2. Guarda la orden formalmente en su base propia (`pedidos360_ordenes`).
3. Publica `orden.registrada` en el topic `p360.eventos` — de ahí la toman
   ms-productos (descuenta stock), ms-notificaciones (manda el correo) y
   ms-auditoria (bitácora).

Expone `GET /ordenes/{ordenId}` para la vista "Mis órdenes" del frontend.

## Desarrollo local

```powershell
docker compose up -d          # base propia (puerto 5434)
# el broker vive en pedidos360-ms-admin/docker/rabbitmq-cluster
$env:RABBITMQ_HOST="localhost"
mvn spring-boot:run           # puerto 8084
```
