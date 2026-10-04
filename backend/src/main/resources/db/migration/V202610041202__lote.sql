-- Lotes con fecha de vencimiento (CCPIP-41) y el detalle de su consulta (CCPIP-161).
--
-- El lote dice cuanto queda de cada ingreso y hasta cuando sirve. La tabla stock sigue
-- existiendo (ADR-10) y se actualiza en la misma transaccion que el lote y su movimiento.
-- Lo que el consumidor ve como disponible es la suma de los lotes vigentes en puntos de
-- acopio: un lote vencido o que sigue en el almacen del productor no se vende.

CREATE TABLE lote (
    id                bigserial     PRIMARY KEY,
    producto_id       bigint        NOT NULL REFERENCES producto(id),
    ubicacion_id      bigint        NOT NULL REFERENCES ubicacion(id),
    codigo            varchar(40)   NOT NULL,
    cantidad_inicial  numeric(12,3) NOT NULL,
    cantidad_actual   numeric(12,3) NOT NULL,
    fecha_produccion  date,
    fecha_vencimiento date,
    registrado_en     timestamptz   NOT NULL DEFAULT now(),
    registrado_por    bigint        REFERENCES usuario(id),
    CONSTRAINT lote_codigo_unico_por_producto UNIQUE (producto_id, codigo),
    CONSTRAINT lote_cantidad_inicial_positiva CHECK (cantidad_inicial > 0),
    -- RNF-09: nunca negativo, y nunca mas de lo que ingreso
    CONSTRAINT lote_cantidad_actual_valida CHECK (cantidad_actual >= 0 AND cantidad_actual <= cantidad_inicial),
    CONSTRAINT lote_vence_despues_de_producir CHECK (
        fecha_vencimiento IS NULL OR fecha_produccion IS NULL OR fecha_vencimiento > fecha_produccion)
);

CREATE INDEX lote_producto_idx ON lote (producto_id);
CREATE INDEX lote_vigente_idx ON lote (producto_id, fecha_vencimiento) WHERE cantidad_actual > 0;

COMMENT ON TABLE lote IS 'CCPIP-41: cada ingreso de mercaderia con su cantidad y vencimiento. El codigo es interno y nunca se expone al consumidor.';
COMMENT ON COLUMN lote.fecha_vencimiento IS 'Nula solo si la linea del producto no requiere vencimiento (p. ej. artesania). No puede ser anterior al dia de registro (validado en el servidor).';

-- Cada movimiento sabe de que lote salio o a cual entro
ALTER TABLE movimiento_inventario ADD COLUMN lote_id bigint REFERENCES lote(id);
CREATE INDEX movimiento_lote_idx ON movimiento_inventario (lote_id);
