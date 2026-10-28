-- CCPIP-34 (RF-11): el productor declara, por producto, cuales reglas de origen y sostenibilidad definidas por
-- la Camara cumple. No hay reglas universales (C11): cada producto declara las suyas y con fecha. Una
-- declaracion no es un sello ni una aprobacion: no se publica como respaldo al visitante (CCPIP-38) y la
-- autorizacion sigue dependiendo de la revision de evidencias.

CREATE TABLE regla_cumplimiento (
    id           smallserial  PRIMARY KEY,
    codigo       varchar(40)  NOT NULL UNIQUE,
    nombre       varchar(80)  NOT NULL,
    nombre_en    varchar(80)  NOT NULL,
    descripcion  varchar(300) NOT NULL,
    descripcion_en varchar(300) NOT NULL,
    activa       boolean      NOT NULL DEFAULT true,
    orden        smallint     NOT NULL DEFAULT 0,
    CONSTRAINT regla_cumplimiento_textos CHECK (btrim(nombre) <> '' AND btrim(nombre_en) <> ''
        AND btrim(descripcion) <> '' AND btrim(descripcion_en) <> '')
);

COMMENT ON TABLE regla_cumplimiento IS
    'CCPIP-34: reglas de origen y sostenibilidad que define la Camara. Se cargan como datos; una inactiva no se declara mas, pero se conserva en las declaraciones anteriores.';

-- Las tres reglas de la propuesta de valor (Definicion de Producto). Son declarables, no obligatorias.
INSERT INTO regla_cumplimiento (codigo, nombre, nombre_en, descripcion, descripcion_en, orden) VALUES
    ('CERO_DEFORESTACION', 'Cero deforestación', 'Zero deforestation',
     'La materia prima no proviene de áreas taladas.', 'The raw material does not come from cleared areas.', 1),
    ('CERO_PESTICIDAS', 'Cero pesticidas o herbicidas', 'Zero pesticides or herbicides',
     'No se usan agroquímicos en el cultivo ni en la transformación.', 'No agrochemicals are used in cultivation or processing.', 2),
    ('ELABORADO_POR_COMUNIDAD', 'Elaborado por comunidad indígena', 'Made by an Indigenous community',
     'Lo elaboran personas de la comunidad indígena del productor.', 'It is made by people from the producer''s Indigenous community.', 3);

-- Cada declaracion es completa (las reglas que el producto cumple a esa fecha) y no se edita: una nueva
-- reemplaza a la anterior, que queda en el historial.
CREATE TABLE declaracion_cumplimiento (
    id            bigserial    PRIMARY KEY,
    producto_id   bigint       NOT NULL REFERENCES producto (id),
    productor_id  bigint       NOT NULL REFERENCES productor (id),
    observaciones varchar(500),
    declarada_por bigint       NOT NULL REFERENCES usuario (id),
    declarada_en  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX declaracion_cumplimiento_producto_idx ON declaracion_cumplimiento (producto_id, declarada_en DESC, id DESC);

CREATE TABLE declaracion_regla (
    declaracion_id bigint   NOT NULL REFERENCES declaracion_cumplimiento (id),
    regla_id       smallint NOT NULL REFERENCES regla_cumplimiento (id),
    PRIMARY KEY (declaracion_id, regla_id)
);

COMMENT ON TABLE declaracion_cumplimiento IS
    'CCPIP-34: declaracion del productor sobre las reglas que cumple un producto propio. La vigente es la mas reciente; no es un sello.';

CREATE FUNCTION declaracion_cumplimiento_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Una declaracion de cumplimiento no se edita ni se borra: se declara otra (CCPIP-34).';
END;
$$;

CREATE TRIGGER declaracion_cumplimiento_sin_cambios
    BEFORE UPDATE OR DELETE ON declaracion_cumplimiento
    FOR EACH ROW EXECUTE FUNCTION declaracion_cumplimiento_inmutable();

CREATE TRIGGER declaracion_regla_sin_cambios
    BEFORE UPDATE OR DELETE ON declaracion_regla
    FOR EACH ROW EXECUTE FUNCTION declaracion_cumplimiento_inmutable();

-- Cada declaracion queda tambien en la auditoria del producto
ALTER TABLE producto_auditoria DROP CONSTRAINT auditoria_accion_valida;
ALTER TABLE producto_auditoria ADD CONSTRAINT auditoria_accion_valida CHECK (accion IN (
    'CREAR', 'EDITAR', 'PUBLICAR', 'RETIRAR', 'REPUBLICAR', 'HISTORIA', 'LOTE', 'IMAGEN',
    'EVIDENCIA', 'AUTORIZACION', 'MODERACION', 'PREMIO', 'CUMPLIMIENTO'));
