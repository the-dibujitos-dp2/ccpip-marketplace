-- CCPIP-176, CCPIP-272, CCPIP-281 y CCPIP-282 (RF-145, RF-146, RF-181, RF-182): mejora
-- opcional de una foto de producto. La variante mejorada se guarda aparte, en su propio objeto
-- del bucket, y nunca sustituye ni se publica sola: queda PENDIENTE hasta que el productor la
-- acepta o la rechaza (RNF-24, RNF-46). Al aceptarla se registra como una imagen nueva que toma
-- el lugar de la original con el mismo mecanismo del reemplazo (CCPIP-388).

CREATE TABLE imagen_mejora (
    id                uuid         PRIMARY KEY,
    imagen_id         uuid         NOT NULL REFERENCES imagen(id),
    estado            varchar(10)  NOT NULL DEFAULT 'PENDIENTE',
    clave             varchar(200) NOT NULL UNIQUE,
    clave_miniatura   varchar(200) NOT NULL UNIQUE,
    ancho             integer      NOT NULL,
    alto              integer      NOT NULL,
    bytes             integer      NOT NULL,
    ajustes           varchar(200) NOT NULL,
    proveedor         varchar(30)  NOT NULL,
    solicitada_por    bigint       NOT NULL REFERENCES usuario(id),
    solicitada_en     timestamptz  NOT NULL DEFAULT now(),
    decidida_por      bigint       REFERENCES usuario(id),
    decidida_en       timestamptz,
    imagen_resultante uuid         REFERENCES imagen(id),
    CONSTRAINT imagen_mejora_estado_valido CHECK (estado IN ('PENDIENTE', 'ACEPTADA', 'RECHAZADA')),
    CONSTRAINT imagen_mejora_decision_completa CHECK (
        (estado = 'PENDIENTE') = (decidida_en IS NULL) AND (decidida_en IS NULL) = (decidida_por IS NULL)),
    -- Solo una mejora aceptada se convierte en imagen publicable
    CONSTRAINT imagen_mejora_resultante CHECK ((estado = 'ACEPTADA') = (imagen_resultante IS NOT NULL))
);

-- Una sola propuesta pendiente por foto: la comparacion siempre es contra una variante
CREATE UNIQUE INDEX imagen_mejora_pendiente_idx ON imagen_mejora (imagen_id) WHERE estado = 'PENDIENTE';

COMMENT ON TABLE imagen_mejora IS 'CCPIP-176: variantes mejoradas por IA. Privadas hasta que el productor las acepta (RF-182).';
COMMENT ON COLUMN imagen_mejora.ajustes IS 'Ajustes globales aplicados (p. ej. AUTOCONTRASTE,NITIDEZ). Nunca se agregan objetos, textos ni sellos.';
COMMENT ON COLUMN imagen_mejora.imagen_resultante IS 'CCPIP-282: la imagen publicada al aceptar; la original queda retirada y apunta a ella.';
