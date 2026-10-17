-- CCPIP-144 y CCPIP-168 (RF-113, RF-137): la IA evalua si una foto de producto corresponde a
-- su linea. Cada evaluacion queda registrada contra la imagen, incluidas las que fallan, y es
-- solo una recomendacion: no bloquea la publicacion ni cambia la foto (RNF-39, RNF-48).
-- La vigente es la ultima solicitada; las anteriores quedan como historial.

CREATE TABLE imagen_validacion (
    id              bigserial    PRIMARY KEY,
    imagen_id       uuid         NOT NULL REFERENCES imagen(id),
    estado          varchar(12)  NOT NULL DEFAULT 'ANALIZANDO',
    linea_esperada  varchar(20)  NOT NULL,
    linea_detectada varchar(20),
    motivo          varchar(300),
    proveedor       varchar(30)  NOT NULL,
    solicitada_por  bigint       NOT NULL REFERENCES usuario(id),
    solicitada_en   timestamptz  NOT NULL DEFAULT now(),
    terminada_en    timestamptz,
    CONSTRAINT imagen_validacion_estado_valido CHECK (estado IN ('ANALIZANDO', 'VALIDA', 'SOSPECHOSA', 'ERROR')),
    -- Mientras se analiza no hay resultado; al terminar, siempre se sabe cuando
    CONSTRAINT imagen_validacion_termino CHECK ((estado = 'ANALIZANDO') = (terminada_en IS NULL)),
    CONSTRAINT imagen_validacion_sospechosa_detecta CHECK (estado <> 'SOSPECHOSA' OR linea_detectada IS NOT NULL)
);

CREATE INDEX imagen_validacion_imagen_idx ON imagen_validacion (imagen_id, solicitada_en DESC, id DESC);

COMMENT ON TABLE imagen_validacion IS 'CCPIP-144: evaluaciones de correspondencia foto-linea. Recomendacion sin bloqueo (RF-137).';
COMMENT ON COLUMN imagen_validacion.linea_esperada IS 'Codigo de la linea principal del producto al momento de evaluar.';
COMMENT ON COLUMN imagen_validacion.proveedor IS 'Quien evaluo: simulado (pruebas y demo) o el modelo que se conecte despues.';
