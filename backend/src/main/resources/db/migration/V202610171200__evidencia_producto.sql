-- CCPIP-36 (RF-13): el productor adjunta certificados, documentos y evidencias a cada producto
-- cuya autorizacion solicita. CCPIP-174 (RF-166) y CCPIP-284 (RF-204): el subadministrador las
-- revisa y aprueba o rechaza con motivo; esa decision humana es la unica que autoriza (S-01, S-02).
--
-- Cada evidencia cuelga de la autorizacion de SU producto y de SU productor, con llave foranea
-- compuesta: un certificado de otro producto no puede quedar como evidencia del solicitado.
-- Al aprobar, autorizacion_producto queda APROBADA, con vigente_hasta igual al certificado que
-- vence primero y apuntando a la decision. Asi la regla de compra de CCPIP-269/270 no cambia.

ALTER TABLE autorizacion_producto
    ADD COLUMN solicitada_en timestamptz,
    ADD CONSTRAINT autorizacion_producto_dueno UNIQUE (id, productor_id, producto_id);

COMMENT ON COLUMN autorizacion_producto.solicitada_en IS 'CCPIP-36: cuando entro (o volvio) a SOLICITADA por una evidencia nueva. Ordena la bandeja S-01.';

CREATE TABLE evidencia_producto (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    autorizacion_id bigint       NOT NULL,
    productor_id    bigint       NOT NULL REFERENCES productor(id),
    producto_id     bigint       NOT NULL REFERENCES producto(id),
    tipo            varchar(12)  NOT NULL,
    titulo          varchar(160) NOT NULL,
    emisor          varchar(160),
    vigente_desde   date,
    vigente_hasta   date,
    nombre_archivo  varchar(200) NOT NULL,
    content_type    varchar(40)  NOT NULL,
    bytes           integer      NOT NULL,
    clave           varchar(200) NOT NULL UNIQUE,
    subida_en       timestamptz  NOT NULL DEFAULT now(),
    subida_por      bigint       NOT NULL REFERENCES usuario(id),
    retirada_en     timestamptz,
    retirada_por    bigint       REFERENCES usuario(id),
    -- La evidencia es del mismo producto y productor que la autorizacion que sustenta (RF-13)
    CONSTRAINT evidencia_de_su_autorizacion FOREIGN KEY (autorizacion_id, productor_id, producto_id)
        REFERENCES autorizacion_producto (id, productor_id, producto_id),
    CONSTRAINT evidencia_tipo_valido CHECK (tipo IN ('CERTIFICADO', 'DOCUMENTO', 'EVIDENCIA')),
    CONSTRAINT evidencia_titulo_presente CHECK (btrim(titulo) <> ''),
    -- Un certificado siempre declara hasta cuando vale; documentos y fotos pueden no vencer
    CONSTRAINT evidencia_certificado_con_vigencia CHECK (tipo <> 'CERTIFICADO' OR vigente_hasta IS NOT NULL),
    CONSTRAINT evidencia_vigencia_ordenada CHECK (vigente_desde IS NULL OR vigente_hasta IS NULL OR vigente_desde <= vigente_hasta),
    CONSTRAINT evidencia_formato_valido CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    CONSTRAINT evidencia_retiro_completo CHECK ((retirada_en IS NULL) = (retirada_por IS NULL))
);

CREATE INDEX evidencia_autorizacion_vigente_idx ON evidencia_producto (autorizacion_id, subida_en) WHERE retirada_en IS NULL;

COMMENT ON TABLE evidencia_producto IS 'CCPIP-36: certificados, documentos y evidencias de un producto. El archivo vive en el bucket privado y solo lo ven el productor y la administracion (RNF-08).';
COMMENT ON COLUMN evidencia_producto.vigente_hasta IS 'Ultimo dia en que la evidencia vale (hora de Lima). Obligatoria en certificados.';
COMMENT ON COLUMN evidencia_producto.retirada_en IS 'Retiro logico: la evidencia deja de sustentar la solicitud, pero se conserva por las decisiones que la citan.';

-- Historial de decisiones humanas. autorizacion_producto guarda el estado vigente; esta tabla,
-- cada decision con su motivo, las evidencias revisadas y quien la tomo. No se edita ni se borra.
CREATE TABLE autorizacion_decision (
    id              bigserial    PRIMARY KEY,
    autorizacion_id bigint       NOT NULL REFERENCES autorizacion_producto(id),
    decision        varchar(10)  NOT NULL,
    motivo          varchar(500) NOT NULL,
    evidencias      uuid[]       NOT NULL,
    vigente_hasta   date,
    decidido_por    bigint       NOT NULL REFERENCES usuario(id),
    decidido_en     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT decision_valida CHECK (decision IN ('APROBADA', 'RECHAZADA')),
    CONSTRAINT decision_motivo_presente CHECK (btrim(motivo) <> ''),
    CONSTRAINT decision_aprobada_con_evidencia CHECK (decision <> 'APROBADA' OR cardinality(evidencias) > 0)
);

CREATE INDEX autorizacion_decision_idx ON autorizacion_decision (autorizacion_id, decidido_en DESC);

COMMENT ON TABLE autorizacion_decision IS 'CCPIP-174 y CCPIP-284: auditoria de cada aprobacion o rechazo. Solo insercion.';
COMMENT ON COLUMN autorizacion_decision.evidencias IS 'Evidencias vigentes que el subadministrador tenia a la vista al decidir.';

ALTER TABLE autorizacion_producto ADD COLUMN decision_id bigint REFERENCES autorizacion_decision(id);

COMMENT ON COLUMN autorizacion_producto.decision_id IS 'CCPIP-174 y CCPIP-284: la decision que fijo el estado actual. Nula en lo aprobado antes de la bandeja (migracion y semillas).';

CREATE OR REPLACE FUNCTION solo_insercion()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% es de solo insercion.', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER autorizacion_decision_sin_update BEFORE UPDATE ON autorizacion_decision
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER autorizacion_decision_sin_delete BEFORE DELETE ON autorizacion_decision
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();

-- El historial del producto (CCPIP-47) tambien registra evidencias y decisiones
ALTER TABLE producto_auditoria DROP CONSTRAINT auditoria_accion_valida;
ALTER TABLE producto_auditoria ADD CONSTRAINT auditoria_accion_valida CHECK (accion IN (
    'CREAR', 'EDITAR', 'PUBLICAR', 'RETIRAR', 'REPUBLICAR', 'HISTORIA', 'LOTE', 'IMAGEN',
    'EVIDENCIA', 'AUTORIZACION'));
