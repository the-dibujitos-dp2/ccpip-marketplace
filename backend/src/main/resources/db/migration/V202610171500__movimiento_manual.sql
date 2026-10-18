-- CCPIP-121 (RF-59), CCPIP-287 (RF-208) y CCPIP-283 (RF-203): entradas y salidas manuales de
-- existencias, del administrador en su punto de acopio y del productor en su almacen.
--
-- Cada movimiento manual dice de que lote, cuanto, por que (motivo), quien (usuario_id) y cuando
-- (registrado_en). El lote, el stock y el movimiento cambian en la misma transaccion (RNF-15, ADR-10).
--
-- Los motivos son un catalogo cerrado y no se solapan con la merma (A-04, A-07), que llega con su
-- historia y sus causas. Tampoco hay "ajuste de conteo": el CHANGELOG del Figma del 30/09 quito la
-- correccion por ajuste del inventario del punto.

-- La salida que no es venta ni transferencia
ALTER TABLE movimiento_inventario DROP CONSTRAINT movimiento_tipo_valido;
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_tipo_valido CHECK (
    tipo IN ('ENTRADA', 'SALIDA', 'SALIDA_VENTA', 'TRANSFERENCIA_ENTRADA', 'TRANSFERENCIA_SALIDA', 'AJUSTE'));

ALTER TABLE movimiento_inventario ADD COLUMN motivo varchar(30);
ALTER TABLE movimiento_inventario ADD COLUMN clave_idempotencia uuid;

-- Cada motivo va con su tipo. Las entradas anteriores (registro del lote, carga inicial) no tienen motivo.
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_motivo_valido CHECK (
    motivo IS NULL
 OR (tipo = 'ENTRADA' AND motivo IN ('RECEPCION', 'PRODUCCION'))
 OR (tipo = 'SALIDA'  AND motivo IN ('DEVOLUCION_PRODUCTOR', 'RETIRO_PRODUCTOR'))
);

-- Una salida manual siempre dice por que, de que lote y quien la registro
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_salida_completa CHECK (
    tipo <> 'SALIDA' OR (motivo IS NOT NULL AND lote_id IS NOT NULL AND usuario_id IS NOT NULL)
);

-- RNF-15: un reintento del mismo formulario (misma clave) no vuelve a mover existencias
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_clave_con_responsable CHECK (
    clave_idempotencia IS NULL OR (usuario_id IS NOT NULL AND motivo IS NOT NULL AND lote_id IS NOT NULL)
);
CREATE UNIQUE INDEX movimiento_clave_unica ON movimiento_inventario (usuario_id, clave_idempotencia)
    WHERE clave_idempotencia IS NOT NULL;

COMMENT ON COLUMN movimiento_inventario.motivo IS 'CCPIP-121, 287, 283: RECEPCION o PRODUCCION en una ENTRADA manual; DEVOLUCION_PRODUCTOR o RETIRO_PRODUCTOR en una SALIDA. Nulo en los movimientos automaticos.';
COMMENT ON COLUMN movimiento_inventario.clave_idempotencia IS 'La genera la pantalla por cada envio del formulario. Con el mismo usuario, una sola fila: el reintento devuelve el movimiento ya registrado.';
