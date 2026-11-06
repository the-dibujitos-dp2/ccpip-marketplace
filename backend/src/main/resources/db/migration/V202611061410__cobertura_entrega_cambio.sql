-- CCPIP-496, 498: el administrador del punto reactiva (activa) un distrito de entrega que desactivo, sin
-- declararlo otra vez. Cada activacion o desactivacion de una zona queda registrada con su autor y aparece
-- en la auditoria (CCPIP-97). Declarar un distrito nuevo o uno inactivo (CCPIP-149) tambien lo activa.

CREATE TABLE cobertura_entrega_cambio (
    id             bigserial   PRIMARY KEY,
    cobertura_id   bigint      NOT NULL REFERENCES cobertura_entrega(id),
    activa         boolean     NOT NULL,
    realizado_por  bigint      NOT NULL REFERENCES usuario(id),
    realizado_en   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX cobertura_entrega_cambio_zona_idx ON cobertura_entrega_cambio (cobertura_id, realizado_en DESC);
CREATE INDEX cobertura_entrega_cambio_fecha_idx ON cobertura_entrega_cambio (realizado_en DESC);

COMMENT ON TABLE cobertura_entrega_cambio IS 'CCPIP-496, 498: quien activo o desactivo cada zona de cobertura y cuando (activa = estado resultante). Solo insercion; aparece en la auditoria (CCPIP-97).';

CREATE FUNCTION cobertura_entrega_cambio_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'cobertura_entrega_cambio es de solo insercion (CCPIP-496).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cobertura_entrega_cambio_sin_update BEFORE UPDATE ON cobertura_entrega_cambio
    FOR EACH ROW EXECUTE FUNCTION cobertura_entrega_cambio_solo_insercion();
CREATE TRIGGER cobertura_entrega_cambio_sin_delete BEFORE DELETE ON cobertura_entrega_cambio
    FOR EACH ROW EXECUTE FUNCTION cobertura_entrega_cambio_solo_insercion();
