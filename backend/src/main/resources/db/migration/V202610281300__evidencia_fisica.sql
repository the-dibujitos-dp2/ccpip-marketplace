-- CCPIP-35 (RF-12) y CCPIP-329 (RF-219): el subadministrador registra el envio y la recepcion de una
-- evidencia fisica de un producto (una muestra, un documento original) cuando su evaluacion la requiera.
-- Es opcional: aprobar no exige ninguna (no se impone muestra obligatoria). Cada registro conserva su
-- resultado y sus observaciones: no se borra ni se reescribe, solo pasa una vez de ENVIADA a RECIBIDA.

CREATE TABLE evidencia_fisica (
    id                       bigserial    PRIMARY KEY,
    autorizacion_id          bigint       NOT NULL,
    productor_id             bigint       NOT NULL,
    producto_id              bigint       NOT NULL,
    descripcion              varchar(300) NOT NULL,
    referencia               varchar(120),
    enviada_el               date         NOT NULL,
    observaciones_envio      varchar(500),
    envio_registrado_por     bigint       NOT NULL REFERENCES usuario (id),
    envio_registrado_en      timestamptz  NOT NULL DEFAULT now(),
    estado                   varchar(10)  NOT NULL DEFAULT 'ENVIADA',
    recibida_el              date,
    resultado                varchar(12),
    observaciones_recepcion  varchar(500),
    recepcion_registrada_por bigint       REFERENCES usuario (id),
    recepcion_registrada_en  timestamptz,
    -- La evidencia es de la misma solicitud: no se registra la de otro producto
    CONSTRAINT evidencia_fisica_de_su_autorizacion FOREIGN KEY (autorizacion_id, productor_id, producto_id)
        REFERENCES autorizacion_producto (id, productor_id, producto_id),
    CONSTRAINT evidencia_fisica_estado_valido CHECK (estado IN ('ENVIADA', 'RECIBIDA')),
    CONSTRAINT evidencia_fisica_resultado_valido CHECK (resultado IN ('CONFORME', 'OBSERVADA', 'NO_CONFORME')),
    CONSTRAINT evidencia_fisica_descripcion_presente CHECK (btrim(descripcion) <> ''),
    CONSTRAINT evidencia_fisica_recepcion_coherente CHECK (
        (estado = 'ENVIADA' AND recibida_el IS NULL AND resultado IS NULL AND observaciones_recepcion IS NULL
            AND recepcion_registrada_por IS NULL AND recepcion_registrada_en IS NULL)
        OR (estado = 'RECIBIDA' AND recibida_el IS NOT NULL AND resultado IS NOT NULL
            AND recepcion_registrada_por IS NOT NULL AND recepcion_registrada_en IS NOT NULL)),
    CONSTRAINT evidencia_fisica_recibida_despues CHECK (recibida_el IS NULL OR recibida_el >= enviada_el)
);

CREATE INDEX evidencia_fisica_autorizacion_idx ON evidencia_fisica (autorizacion_id, envio_registrado_en DESC);

COMMENT ON TABLE evidencia_fisica IS
    'CCPIP-35 y CCPIP-329: envio y recepcion de evidencias fisicas de la solicitud de un producto. Opcionales: aprobar no las exige.';
COMMENT ON COLUMN evidencia_fisica.referencia IS 'Guia, codigo de seguimiento o quien la lleva. Opcional.';
COMMENT ON COLUMN evidencia_fisica.resultado IS
    'Lo que encontro la Camara al recibirla. Es un insumo de la revision: no aprueba ni rechaza la solicitud.';

CREATE FUNCTION evidencia_fisica_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Una evidencia fisica registrada no se borra (CCPIP-35).';
    END IF;
    IF OLD.estado <> 'ENVIADA'
       OR (NEW.autorizacion_id, NEW.productor_id, NEW.producto_id, NEW.descripcion, NEW.referencia, NEW.enviada_el,
           NEW.observaciones_envio, NEW.envio_registrado_por, NEW.envio_registrado_en)
          IS DISTINCT FROM
          (OLD.autorizacion_id, OLD.productor_id, OLD.producto_id, OLD.descripcion, OLD.referencia, OLD.enviada_el,
           OLD.observaciones_envio, OLD.envio_registrado_por, OLD.envio_registrado_en) THEN
        RAISE EXCEPTION 'Una evidencia fisica solo pasa una vez de ENVIADA a RECIBIDA (CCPIP-329).';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER evidencia_fisica_sin_cambios
    BEFORE UPDATE OR DELETE ON evidencia_fisica
    FOR EACH ROW EXECUTE FUNCTION evidencia_fisica_inmutable();
