-- CCPIP-158 (RF-127): cada venta confirmada descuenta existencias con un movimiento SALIDA_VENTA
-- por linea de pedido y lote, que el administrador del punto de acopio consulta.

-- La linea de pedido a la que se asigno el lote por vencimiento mas proximo (diccionario de datos).
ALTER TABLE movimiento_inventario ADD COLUMN item_pedido_id bigint REFERENCES item_pedido(id);

-- Una venta siempre dice de que pedido, que linea y que lote salio.
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_venta_completa CHECK (
    tipo <> 'SALIDA_VENTA' OR (pedido_id IS NOT NULL AND item_pedido_id IS NOT NULL AND lote_id IS NOT NULL)
);

-- RNF-10: idempotencia en la base. Una linea de pedido descuenta un lote una sola vez, aunque la
-- confirmacion se reintente: un segundo descuento choca aqui en vez de restar dos veces.
CREATE UNIQUE INDEX movimiento_venta_unica ON movimiento_inventario (item_pedido_id, lote_id)
    WHERE tipo = 'SALIDA_VENTA';

COMMENT ON COLUMN movimiento_inventario.item_pedido_id IS 'Linea de pedido que origina una SALIDA_VENTA. Una linea puede salir de varios lotes: un movimiento por lote.';
