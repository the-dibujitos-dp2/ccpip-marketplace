-- CCPIP-118 (RF-10), CCPIP-390 (RF-282), CCPIP-391 (RF-283) y CCPIP-392 (RF-284): videos de la tienda.
-- Acordado el 26/10 (RNF-25): el productor sube sus propios archivos MP4 o WebM de hasta 50 MB y 3 minutos,
-- hasta 5 por tienda. Como las ofertas (CCPIP-46), cada video pasa por la revision del subadministrador
-- antes de verse en la tienda publica. Nada se borra: un video se retira de forma logica o se reemplaza
-- por otro que apunta a el.

CREATE TABLE video_tienda (
    id                 bigserial     PRIMARY KEY,
    productor_id       bigint        NOT NULL REFERENCES productor (id),
    titulo             varchar(120)  NOT NULL,
    clave              varchar(255)  NOT NULL UNIQUE,
    formato            varchar(4)    NOT NULL,
    bytes              bigint        NOT NULL,
    duracion_segundos  numeric(6, 2) NOT NULL,
    -- CCPIP-391: el video que este reemplaza; el anterior sigue publicado hasta que se aprueba el nuevo
    reemplaza_a        bigint        REFERENCES video_tienda (id),
    subido_por         bigint        NOT NULL REFERENCES usuario (id),
    subido_en          timestamptz   NOT NULL DEFAULT now(),
    estado             varchar(12)   NOT NULL DEFAULT 'EN_REVISION',
    motivo             varchar(500),
    decidido_por       bigint        REFERENCES usuario (id),
    decidido_en        timestamptz,
    -- CCPIP-392: retiro logico, con autor y fecha
    retirado_por       bigint        REFERENCES usuario (id),
    retirado_en        timestamptz,
    reemplazado_por    bigint        REFERENCES video_tienda (id),
    CONSTRAINT video_titulo_valido CHECK (btrim(titulo) <> ''),
    CONSTRAINT video_formato_valido CHECK (formato IN ('MP4', 'WEBM')),
    CONSTRAINT video_peso_valido CHECK (bytes > 0 AND bytes <= 52428800),
    CONSTRAINT video_duracion_valida CHECK (duracion_segundos > 0 AND duracion_segundos <= 180),
    CONSTRAINT video_estado_valido CHECK (estado IN ('EN_REVISION', 'PUBLICADO', 'RECHAZADO')),
    -- Toda decision humana lleva autor, fecha y motivo
    CONSTRAINT video_decision_completa CHECK (
        (estado = 'EN_REVISION' AND motivo IS NULL AND decidido_por IS NULL AND decidido_en IS NULL)
        OR (estado <> 'EN_REVISION' AND btrim(motivo) <> '' AND decidido_por IS NOT NULL AND decidido_en IS NOT NULL)),
    CONSTRAINT video_retiro_completo CHECK ((retirado_por IS NULL) = (retirado_en IS NULL))
);

CREATE INDEX video_productor_idx ON video_tienda (productor_id, subido_en);
CREATE INDEX video_en_revision_idx ON video_tienda (subido_en) WHERE estado = 'EN_REVISION' AND retirado_en IS NULL;
-- Un solo reemplazo en revision por video
CREATE UNIQUE INDEX video_un_reemplazo_idx ON video_tienda (reemplaza_a)
    WHERE estado = 'EN_REVISION' AND retirado_en IS NULL;

COMMENT ON TABLE video_tienda IS
    'CCPIP-118, CCPIP-390, CCPIP-391 y CCPIP-392: videos propios de la tienda, su revision y su retiro logico.';

-- El archivo y su autoria no cambian; la decision se toma una vez y el retiro no se deshace
CREATE FUNCTION video_tienda_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Un video no se borra: se retira (CCPIP-392).';
    END IF;
    IF (NEW.productor_id, NEW.titulo, NEW.clave, NEW.formato, NEW.bytes, NEW.duracion_segundos, NEW.reemplaza_a,
        NEW.subido_por, NEW.subido_en)
       IS DISTINCT FROM
       (OLD.productor_id, OLD.titulo, OLD.clave, OLD.formato, OLD.bytes, OLD.duracion_segundos, OLD.reemplaza_a,
        OLD.subido_por, OLD.subido_en) THEN
        RAISE EXCEPTION 'El archivo de un video no se modifica: se reemplaza (CCPIP-391).';
    END IF;
    IF OLD.estado <> 'EN_REVISION'
       AND (NEW.estado, NEW.motivo, NEW.decidido_por, NEW.decidido_en)
           IS DISTINCT FROM (OLD.estado, OLD.motivo, OLD.decidido_por, OLD.decidido_en) THEN
        RAISE EXCEPTION 'La revision de un video solo se decide una vez (CCPIP-118).';
    END IF;
    -- Al reemplazar uno que aun no se veia, se retira primero y luego se anota el nuevo, una sola vez
    IF OLD.retirado_en IS NOT NULL
       AND ((NEW.retirado_por, NEW.retirado_en) IS DISTINCT FROM (OLD.retirado_por, OLD.retirado_en)
            OR (OLD.reemplazado_por IS NOT NULL AND NEW.reemplazado_por IS DISTINCT FROM OLD.reemplazado_por)) THEN
        RAISE EXCEPTION 'Un video retirado no vuelve (CCPIP-392).';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER video_tienda_sin_cambios
    BEFORE UPDATE OR DELETE ON video_tienda
    FOR EACH ROW EXECUTE FUNCTION video_tienda_inmutable();

-- La decision se avisa al productor por correo, como la de sus ofertas
ALTER TABLE aviso DROP CONSTRAINT aviso_tipo_valido;
ALTER TABLE aviso ADD CONSTRAINT aviso_tipo_valido CHECK (tipo IN (
    'PEDIDO_LISTO', 'INTERES_VENDIBLE', 'DECISION_AUTORIZACION', 'DECISION_PRODUCTOR', 'RENOVACION_CERTIFICADO',
    'DECISION_MODERACION', 'DECISION_VIDEO'));
