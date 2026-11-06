-- CCPIP-493: una oferta se rechaza a lo mas 3 veces. Al tercer rechazo el productor ya no puede volver a
-- enviarla; solo el subadministrador habilita un nuevo intento, con motivo. Habilitarlo reinicia la cuenta:
-- se cuentan los rechazos de las solicitudes posteriores a la del ultimo intento habilitado.

CREATE TABLE reintento_publicacion (
    id             bigserial    PRIMARY KEY,
    producto_id    bigint       NOT NULL REFERENCES producto (id),
    -- La ultima solicitud rechazada cuando se habilito: desde ella se vuelve a contar
    solicitud_id   bigint       NOT NULL UNIQUE REFERENCES moderacion_publicacion (id),
    habilitado_por bigint       NOT NULL REFERENCES usuario (id),
    motivo         varchar(500) NOT NULL,
    habilitado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT reintento_motivo_requerido CHECK (btrim(motivo) <> '')
);

CREATE INDEX reintento_producto_idx ON reintento_publicacion (producto_id, habilitado_en DESC);

COMMENT ON TABLE reintento_publicacion IS
    'CCPIP-493: cada nuevo intento de publicacion que el subadministrador habilita tras el tercer rechazo.';

-- Queda como constancia: no se borra ni se reescribe
CREATE FUNCTION reintento_publicacion_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Un intento habilitado no se modifica ni se borra (CCPIP-493).';
END;
$$;

CREATE TRIGGER reintento_publicacion_sin_cambios
    BEFORE UPDATE OR DELETE ON reintento_publicacion
    FOR EACH ROW EXECUTE FUNCTION reintento_publicacion_inmutable();
