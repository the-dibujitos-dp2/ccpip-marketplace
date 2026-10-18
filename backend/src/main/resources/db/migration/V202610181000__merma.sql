-- Merma de existencias (Figma A-04, A-06, A-07 y PW "Merma"):
--   CCPIP-76 (RF-61)  marcar existencias de un lote como merma con cantidad y causa, con confirmacion
--                     expresa e irreversible: el productor en su almacen, el administrador en sus puntos.
--   CCPIP-75 (RF-60)  merma automatica de los lotes vencidos: un movimiento idempotente por lote, con
--                     causa (Vencimiento) y fecha, que no se edita.
--   CCPIP-77, 298, 299 y 300 (RF-63, 174-176): catalogo controlado de causas que el superadministrador
--                     consulta, crea, modifica y desactiva sin alterar la merma ya registrada.

-- ---------- Catalogo de causas ----------

CREATE TABLE motivo_merma (
    id               bigserial    PRIMARY KEY,
    nombre           varchar(60)  NOT NULL,
    nombre_en        varchar(60)  NOT NULL,
    -- "Otra (especificar)": quien marca la merma debe describir la causa
    requiere_detalle boolean      NOT NULL DEFAULT false,
    activo           boolean      NOT NULL DEFAULT true,
    -- La causa que usa la merma automatica (Vencimiento): se puede renombrar, no desactivar
    sistema          boolean      NOT NULL DEFAULT false,
    actualizado_por  bigint       REFERENCES usuario(id),
    actualizado_en   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT motivo_merma_nombre_no_vacio CHECK (btrim(nombre) <> '' AND btrim(nombre_en) <> ''),
    CONSTRAINT motivo_merma_sistema_activo CHECK (NOT sistema OR activo)
);

CREATE UNIQUE INDEX motivo_merma_nombre_unico ON motivo_merma (lower(btrim(nombre)));
CREATE UNIQUE INDEX motivo_merma_nombre_en_unico ON motivo_merma (lower(btrim(nombre_en)));
CREATE UNIQUE INDEX motivo_merma_un_sistema ON motivo_merma (sistema) WHERE sistema;

COMMENT ON TABLE motivo_merma IS
    'CCPIP-77: causas de merma. Cambiar o desactivar una causa no toca la merma registrada, que guarda el nombre con el que se marco.';

-- Cada alta, cambio o desactivacion queda registrada: quien, cuando y como quedo la causa
CREATE TABLE motivo_merma_cambio (
    id               bigserial    PRIMARY KEY,
    motivo_id        bigint       NOT NULL REFERENCES motivo_merma(id),
    accion           varchar(12)  NOT NULL,
    nombre           varchar(60)  NOT NULL,
    nombre_en        varchar(60)  NOT NULL,
    requiere_detalle boolean      NOT NULL,
    activo           boolean      NOT NULL,
    registrado_por   bigint       REFERENCES usuario(id),
    registrado_en    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT motivo_merma_cambio_accion CHECK (accion IN ('CREADO', 'MODIFICADO', 'DESACTIVADO', 'ACTIVADO'))
);

CREATE INDEX motivo_merma_cambio_motivo_idx ON motivo_merma_cambio (motivo_id, registrado_en DESC);

-- Causas del Figma A-04. Vencimiento es la de la merma automatica.
INSERT INTO motivo_merma (nombre, nombre_en, requiere_detalle, sistema) VALUES
    ('Vencimiento', 'Expiry', false, true),
    ('Daño', 'Damage', false, false),
    ('Accidente', 'Accident', false, false),
    ('Derrame', 'Spill', false, false),
    ('Pérdida', 'Loss', false, false),
    ('Otra', 'Other', true, false);

INSERT INTO motivo_merma_cambio (motivo_id, accion, nombre, nombre_en, requiere_detalle, activo)
SELECT id, 'CREADO', nombre, nombre_en, requiere_detalle, activo FROM motivo_merma;

-- ---------- Merma ----------

-- La merma es una salida de existencias: un movimiento MERMA en el lote, como la venta o la transferencia
ALTER TABLE movimiento_inventario DROP CONSTRAINT movimiento_tipo_valido;
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_tipo_valido CHECK (
    tipo IN ('ENTRADA', 'SALIDA', 'SALIDA_VENTA', 'TRANSFERENCIA_ENTRADA', 'TRANSFERENCIA_SALIDA', 'AJUSTE', 'MERMA'));
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_merma_con_lote CHECK (tipo <> 'MERMA' OR lote_id IS NOT NULL);

-- El detalle de cada merma: causa (copiada del catalogo al marcarla), responsable y si fue automatica
CREATE TABLE merma (
    movimiento_id     bigint        PRIMARY KEY REFERENCES movimiento_inventario(id),
    lote_id           bigint        NOT NULL REFERENCES lote(id),
    motivo_id         bigint        NOT NULL REFERENCES motivo_merma(id),
    -- El nombre de la causa al marcarla: renombrarla despues no cambia la historia (CCPIP-299)
    motivo_nombre     varchar(60)   NOT NULL,
    motivo_nombre_en  varchar(60)   NOT NULL,
    detalle           varchar(200),
    automatica        boolean       NOT NULL,
    -- Nulo en la automatica: la registra el sistema
    registrado_por    bigint        REFERENCES usuario(id),
    registrado_en     timestamptz   NOT NULL DEFAULT now(),
    -- Un reintento del mismo formulario no marca dos veces (RNF-15)
    clave_idempotencia uuid,
    CONSTRAINT merma_responsable CHECK (automatica = (registrado_por IS NULL)),
    CONSTRAINT merma_clave_manual CHECK (clave_idempotencia IS NULL OR NOT automatica)
);

-- CCPIP-75: una sola merma automatica por lote, aunque la revision corra muchas veces
CREATE UNIQUE INDEX merma_automatica_unica ON merma (lote_id) WHERE automatica;
CREATE UNIQUE INDEX merma_clave_unica ON merma (registrado_por, clave_idempotencia) WHERE clave_idempotencia IS NOT NULL;
CREATE INDEX merma_lote_idx ON merma (lote_id);
CREATE INDEX merma_registrada_idx ON merma (registrado_en DESC);

COMMENT ON TABLE merma IS
    'CCPIP-75 y CCPIP-76: merma de un lote, manual (con confirmacion) o automatica por vencimiento. Irreversible: no se edita ni se borra.';

-- RNF-16: la merma, como el movimiento, no se edita ni se borra
CREATE OR REPLACE FUNCTION merma_inmutable()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'La merma no se edita ni se borra (RNF-16, CCPIP-76).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER merma_sin_cambios BEFORE UPDATE OR DELETE ON merma
    FOR EACH ROW EXECUTE FUNCTION merma_inmutable();
