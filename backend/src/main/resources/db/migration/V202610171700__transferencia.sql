-- Transferencias de existencias entre ubicaciones (RNF-47).
-- CCPIP-73 (RF-67): el productor inicia una transferencia de su almacen a un punto de acopio.
-- CCPIP-277 (RF-177): el productor solicita una transferencia entre puntos de acopio.
-- CCPIP-164 (RF-133) y CCPIP-362 (RF-252): preparacion y despacho desde su almacen.
-- CCPIP-278 (RF-178): el administrador del punto consulta el estado y el historial, y recibe.
--
-- Invariante central: las unidades no se duplican ni se pierden. En todo momento
--   saldo de los lotes de origen + unidades separadas (PREPARADA) + en transito (DESPACHADA)
--   + lotes de destino
-- se conserva. Solo el lote cambia de cantidad, y siempre con su movimiento en la misma
-- transaccion (RNF-15, ADR-10):
--   * PREPARAR: el lote de origen baja con un movimiento TRANSFERENCIA_SALIDA. Las unidades
--     quedan comprometidas con la transferencia y ya no se pueden vender, preparar ni despachar
--     otra vez desde el lote: asi no hay doble descuento.
--   * DESPACHAR: no toca ningun lote (ya se desconto al preparar); las unidades pasan a transito,
--     que no es vendible porque no estan en un lote de un punto de acopio (CCPIP-162).
--   * RECIBIR: el administrador del destino crea el lote de destino con un movimiento
--     TRANSFERENCIA_ENTRADA; recien ahi el catalogo las cuenta.
--
-- Los tipos TRANSFERENCIA_ENTRADA y TRANSFERENCIA_SALIDA ya existen en el CHECK
-- movimiento_tipo_valido desde V202610020035: esta migracion no lo modifica (solo amplia la columna).
--
-- Responsables pendientes (C8): quien aprueba una transferencia entre puntos no esta definido.
-- Se registra como SOLICITADA, visible para los administradores de ambos puntos, y no avanza
-- hasta que se defina el flujo de aprobacion.

CREATE TABLE transferencia (
    id               bigserial    PRIMARY KEY,
    tipo             varchar(20)  NOT NULL,
    productor_id     bigint       NOT NULL REFERENCES productor(id),
    origen_id        bigint       NOT NULL REFERENCES ubicacion(id),
    destino_id       bigint       NOT NULL REFERENCES ubicacion(id),
    estado           varchar(20)  NOT NULL,
    observacion      varchar(255),
    creada_por       bigint       REFERENCES usuario(id),
    creada_en        timestamptz  NOT NULL DEFAULT now(),
    actualizada_en   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT transferencia_tipo_valido CHECK (tipo IN ('ALMACEN_A_PUNTO', 'ENTRE_PUNTOS')),
    CONSTRAINT transferencia_estado_valido CHECK (
        estado IN ('INICIADA', 'SOLICITADA', 'PREPARADA', 'DESPACHADA', 'RECIBIDA')),
    CONSTRAINT transferencia_origen_distinto CHECK (origen_id <> destino_id)
);

CREATE INDEX transferencia_productor_idx ON transferencia (productor_id, creada_en DESC);
CREATE INDEX transferencia_origen_idx ON transferencia (origen_id);
CREATE INDEX transferencia_destino_idx ON transferencia (destino_id);

COMMENT ON TABLE transferencia IS 'RNF-47: traslado de existencias de un productor entre ubicaciones. Origen, destino, estado y fechas; el detalle va en transferencia_item.';
COMMENT ON COLUMN transferencia.estado IS 'INICIADA (almacen a punto, sin comprometer unidades aun), SOLICITADA (entre puntos, espera aprobador C8), PREPARADA (unidades separadas del lote de origen), DESPACHADA (en transito, no vendible), RECIBIDA (en un lote del destino).';

-- Que lote y cuanto. Un lote aparece una sola vez por transferencia.
CREATE TABLE transferencia_item (
    id                bigserial     PRIMARY KEY,
    transferencia_id  bigint        NOT NULL REFERENCES transferencia(id),
    producto_id       bigint        NOT NULL REFERENCES producto(id),
    lote_origen_id    bigint        NOT NULL REFERENCES lote(id),
    cantidad          numeric(12,3) NOT NULL,
    lote_destino_id   bigint        REFERENCES lote(id),
    CONSTRAINT transferencia_item_cantidad_positiva CHECK (cantidad > 0),
    CONSTRAINT transferencia_item_lote_unico UNIQUE (transferencia_id, lote_origen_id)
);

CREATE INDEX transferencia_item_lote_origen_idx ON transferencia_item (lote_origen_id);

COMMENT ON COLUMN transferencia_item.lote_destino_id IS 'Lote creado en el destino al recibir. Nulo hasta la recepcion.';

-- Cada cambio de estado, con quien y cuando (CCPIP-278).
CREATE TABLE transferencia_historial (
    id                bigserial    PRIMARY KEY,
    transferencia_id  bigint       NOT NULL REFERENCES transferencia(id),
    estado            varchar(20)  NOT NULL,
    usuario_id        bigint       REFERENCES usuario(id),
    registrado_en     timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX transferencia_historial_idx ON transferencia_historial (transferencia_id, registrado_en);

-- 'TRANSFERENCIA_ENTRADA' tiene 21 caracteres y no cabia en varchar(20): nunca se habia usado.
-- Ampliar un varchar no reescribe la tabla ni dispara los triggers de solo insercion.
ALTER TABLE movimiento_inventario ALTER COLUMN tipo TYPE varchar(30);

-- El movimiento sabe a que linea de transferencia pertenece (CCPIP-164, CCPIP-362)
ALTER TABLE movimiento_inventario ADD COLUMN transferencia_item_id bigint REFERENCES transferencia_item(id);
CREATE INDEX movimiento_transferencia_item_idx ON movimiento_inventario (transferencia_item_id);

-- Un movimiento de transferencia siempre dice de que linea y de que lote
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_transferencia_completa CHECK (
    tipo NOT IN ('TRANSFERENCIA_ENTRADA', 'TRANSFERENCIA_SALIDA')
    OR (transferencia_item_id IS NOT NULL AND lote_id IS NOT NULL)
);

-- Idempotencia en la base: una linea sale una sola vez y entra una sola vez. Un segundo intento
-- de preparar o recibir choca aqui en vez de descontar o sumar dos veces.
CREATE UNIQUE INDEX movimiento_transferencia_unica ON movimiento_inventario (transferencia_item_id, tipo)
    WHERE transferencia_item_id IS NOT NULL;
