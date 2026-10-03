-- V4: inventario
-- Cubre CCPIP-158 (descontar stock al confirmar) y CCPIP-72 (consultar stock).
--
-- DECISION IMPORTANTE, no cambiar sin leer ADR-10:
-- stock es una TABLA, no una vista. Se actualiza en la misma transaccion que cada
-- movimiento. Si fuera una vista que suma movimientos, se perderia la restriccion
-- de cantidad no negativa y cada lectura del catalogo seria una agregacion.
--
-- El stock virtual unico que ve el consumidor (RNF-15) es la SUMA de las filas de
-- stock de un producto en todas sus ubicaciones. No se guarda ese total en ninguna
-- parte, se calcula al consultar.

CREATE TABLE stock (
    producto_id     bigint       NOT NULL REFERENCES producto(id),
    ubicacion_id    bigint       NOT NULL REFERENCES ubicacion(id),
    cantidad        numeric(12,3) NOT NULL DEFAULT 0,
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (producto_id, ubicacion_id),
    -- RNF-09: las existencias nunca pueden quedar en valor negativo.
    -- Esta restriccion es lo que convierte una sobreventa en un error de base de datos
    -- en vez de en un dato corrupto que nadie nota.
    CONSTRAINT stock_no_negativo CHECK (cantidad >= 0)
);

COMMENT ON TABLE stock IS 'Existencias por producto y ubicacion. RNF-15: no se edita a mano, se actualiza siempre junto a un movimiento, en la misma transaccion.';

-- RNF-15: las existencias se explican por los movimientos registrados.
-- Tabla de solo insercion: ni UPDATE ni DELETE.
CREATE TABLE movimiento_inventario (
    id              bigserial    PRIMARY KEY,
    producto_id     bigint       NOT NULL REFERENCES producto(id),
    ubicacion_id    bigint       NOT NULL REFERENCES ubicacion(id),
    tipo            varchar(20)  NOT NULL,
    cantidad        numeric(12,3) NOT NULL,
    pedido_id       bigint,
    usuario_id      bigint       REFERENCES usuario(id),
    observacion     varchar(255),
    registrado_en   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT movimiento_tipo_valido CHECK (tipo IN ('ENTRADA', 'SALIDA_VENTA', 'TRANSFERENCIA_ENTRADA', 'TRANSFERENCIA_SALIDA', 'AJUSTE')),
    CONSTRAINT movimiento_cantidad_positiva CHECK (cantidad > 0)
);

CREATE INDEX movimiento_producto_ubicacion_idx ON movimiento_inventario (producto_id, ubicacion_id);
CREATE INDEX movimiento_pedido_idx ON movimiento_inventario (pedido_id);

COMMENT ON COLUMN movimiento_inventario.cantidad IS 'Siempre positiva. El signo lo da el tipo, no el numero. Asi una consulta que suma mal es evidente en lugar de silenciosa.';
COMMENT ON COLUMN movimiento_inventario.pedido_id IS 'Llave foranea al pedido, agregada en V5 cuando la tabla pedido ya existe.';

-- RNF-15 y RNF-16: un movimiento no se edita ni se borra. Una correccion se hace
-- con un movimiento de AJUSTE que conserva el original.
-- Esto se impone en la base y no solo en la aplicacion, porque una regla que solo
-- vive en el codigo se salta desde cualquier cliente SQL.
CREATE OR REPLACE FUNCTION movimiento_solo_insercion()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'movimiento_inventario es de solo insercion (RNF-15). Para corregir, registra un movimiento de tipo AJUSTE.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER movimiento_sin_update
    BEFORE UPDATE ON movimiento_inventario
    FOR EACH ROW EXECUTE FUNCTION movimiento_solo_insercion();

CREATE TRIGGER movimiento_sin_delete
    BEFORE DELETE ON movimiento_inventario
    FOR EACH ROW EXECUTE FUNCTION movimiento_solo_insercion();
