-- CCPIP-119 (RF-162): el productor pide a la Camara una unidad de medida que no existe en el catalogo
-- cerrado (RNF-32). CCPIP-137 (RF-114) y CCPIP-353 (RF-243): el superadministrador, que administra el
-- catalogo por la Camara, la aprueba o la rechaza con su motivo. Solo al aprobarla entra a unidad_medida:
-- antes no se puede usar en ninguna oferta.

CREATE TABLE solicitud_unidad_medida (
    id                bigserial     PRIMARY KEY,
    productor_id      bigint        NOT NULL REFERENCES productor(id),
    solicitada_por    bigint        NOT NULL REFERENCES usuario(id),
    nombre            varchar(60)   NOT NULL,
    justificacion     varchar(500)  NOT NULL,
    estado            varchar(10)   NOT NULL DEFAULT 'PENDIENTE',
    motivo            varchar(500),
    decidida_por      bigint        REFERENCES usuario(id),
    decidida_en       timestamptz,
    unidad_medida_id  smallint      REFERENCES unidad_medida(id),
    creada_en         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT solicitud_unidad_estado_valido CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA')),
    CONSTRAINT solicitud_unidad_decision_completa CHECK (
        (estado = 'PENDIENTE' AND motivo IS NULL AND decidida_por IS NULL AND decidida_en IS NULL AND unidad_medida_id IS NULL)
        OR (estado = 'APROBADA' AND motivo IS NOT NULL AND decidida_por IS NOT NULL AND decidida_en IS NOT NULL AND unidad_medida_id IS NOT NULL)
        OR (estado = 'RECHAZADA' AND motivo IS NOT NULL AND decidida_por IS NOT NULL AND decidida_en IS NOT NULL AND unidad_medida_id IS NULL))
);

CREATE INDEX solicitud_unidad_estado_idx ON solicitud_unidad_medida (estado, creada_en);
CREATE INDEX solicitud_unidad_productor_idx ON solicitud_unidad_medida (productor_id, creada_en DESC);

-- Un productor no repite una solicitud pendiente con el mismo nombre (sin distinguir mayusculas ni tildes)
CREATE UNIQUE INDEX solicitud_unidad_pendiente_unica_idx
    ON solicitud_unidad_medida (productor_id, lower(inmutable_unaccent(btrim(nombre))))
    WHERE estado = 'PENDIENTE';

-- Una solicitud se decide una sola vez y despues no cambia ni se borra
CREATE FUNCTION solicitud_unidad_medida_inmutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Una solicitud de unidad de medida no se borra (CCPIP-119).';
    END IF;
    IF OLD.estado <> 'PENDIENTE' THEN
        RAISE EXCEPTION 'Una solicitud de unidad de medida ya decidida no cambia (CCPIP-137, CCPIP-353).';
    END IF;
    IF NEW.productor_id <> OLD.productor_id OR NEW.solicitada_por <> OLD.solicitada_por
       OR NEW.nombre <> OLD.nombre OR NEW.justificacion <> OLD.justificacion OR NEW.creada_en <> OLD.creada_en THEN
        RAISE EXCEPTION 'Lo que pidio el productor no cambia (CCPIP-119).';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solicitud_unidad_medida_inmutable BEFORE UPDATE OR DELETE ON solicitud_unidad_medida
    FOR EACH ROW EXECUTE FUNCTION solicitud_unidad_medida_inmutable();

-- La unidad que nace de una solicitud aprobada queda en la auditoria del catalogo
ALTER TABLE catalogo_maestro_auditoria DROP CONSTRAINT catalogo_auditoria_entidad_valida;
ALTER TABLE catalogo_maestro_auditoria ADD CONSTRAINT catalogo_auditoria_entidad_valida
    CHECK (entidad IN ('LINEA', 'PRODUCTO', 'UNIDAD'));

COMMENT ON TABLE solicitud_unidad_medida IS 'CCPIP-119: unidades que un productor pide a la Camara. Pendiente no se usa; aprobada crea la unidad (CCPIP-137); rechazada guarda el motivo (CCPIP-353).';
COMMENT ON COLUMN solicitud_unidad_medida.justificacion IS 'Para que la necesita el productor: por que ninguna del catalogo le sirve.';
COMMENT ON COLUMN solicitud_unidad_medida.unidad_medida_id IS 'La unidad creada al aprobarla.';
