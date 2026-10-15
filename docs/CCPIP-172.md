# CCPIP-172: conservar la oferta y el productor elegidos

Requisito: [CCPIP-172 / RF-144](https://dp2-dibuj.atlassian.net/browse/CCPIP-172).

En este modelo cada `producto.id` es una oferta concreta de un productor. Dos productores pueden publicar productos con el mismo nombre; el comprador elige la ficha del catalogo o de la tienda. El carrito mantiene ambas ofertas separadas por identificador, nunca por nombre.

La migracion `V202610151800__oferta_productor.sql` impide reasignar una oferta a otro productor y exige que el productor copiado al pedido corresponda a esa oferta. Para vender desde otro productor se crea una nueva oferta, que el comprador debe elegir expresamente.

El resumen anterior al pago muestra la tienda de cada linea. La respuesta del pedido, incluida en el resultado del pago, devuelve `productoId` y `productorId`. El descuento utiliza exclusivamente los lotes del producto elegido en el punto de recojo. Si se agota, se anula el pago y se cancela el pedido; no se consume stock de ofertas alternativas.

Evidencia automatizada en `CompraFlujoTest`:

- Ofertas de igual nombre y distintos productores permanecen separadas en carrito, pedido, pago y movimientos de inventario.
- Agotar la oferta elegida no consume existencias de otra oferta del mismo nombre.
- La base rechaza cambios de productor y pedidos con un productor ajeno a la oferta.

`Compra.test.jsx` comprueba que el resumen de pago distingue los productores incluso si los nombres de producto coinciden.

## Validacion local (15 de octubre de 2026)

Ejecutada en contenedores temporales de Docker Desktop, con las dependencias del repositorio y sin cambios en `docker-compose.yml`:

- Backend, Java 21: `sh ./mvnw -B -ntp verify`. 277 pruebas aprobadas, sin fallos ni errores; JAR generado.
- Frontend, Node 22: `npm run build`. Compilacion de produccion correcta.
- Frontend: `npm test -- --maxWorkers=1 --testTimeout=15000 --reporter=dot`. 245 pruebas aprobadas en 19 archivos.

La ejecucion inicial del frontend con paralelismo y tiempos predeterminados produjo esperas agotadas. Se limito la concurrencia y se amplio el margen por prueba solo en el comando de validacion, sin modificar la configuracion compartida. Los cuatro casos que seguian fallando con dos procesos tambien se comprobaron contra HEAD y pasaron con un proceso y el mismo margen.

Dos pruebas antiguas de carrito reasignaban el productor para preparar sus datos. Ahora crean directamente la oferta del productor correspondiente, conforme a RF-144. Los saltos de linea de `mvnw` se normalizaron unicamente en la copia temporal del contenedor para ejecutarlo en Linux.
