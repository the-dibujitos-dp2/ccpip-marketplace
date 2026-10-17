-- CCPIP-50 (RF-74): el subadministrador pide a la IA un analisis preliminar de las evidencias de
-- una solicitud. Es solo una ayuda: nunca cambia el estado de la autorizacion (RNF-30). Cada
-- hallazgo apunta a la evidencia que lo origina, para que la recomendacion sea explicable (RNF-48).
-- Si el servicio no responde queda registrado como NO_DISPONIBLE y la solicitud se revisa a mano.

CREATE TABLE analisis_evidencias (
    id              bigserial    PRIMARY KEY,
    autorizacion_id bigint       NOT NULL REFERENCES autorizacion_producto(id),
    estado          varchar(14)  NOT NULL,
    proveedor       varchar(40)  NOT NULL,
    simulado        boolean      NOT NULL DEFAULT false,
    recomendacion   varchar(20),
    resumen         text,
    evidencias      uuid[]       NOT NULL,
    solicitado_por  bigint       NOT NULL REFERENCES usuario(id),
    solicitado_en   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT analisis_estado_valido CHECK (estado IN ('COMPLETADO', 'NO_DISPONIBLE')),
    CONSTRAINT analisis_recomendacion_valida CHECK (recomendacion IS NULL OR recomendacion IN ('SIN_OBSERVACIONES', 'REVISAR')),
    CONSTRAINT analisis_completo_con_recomendacion CHECK ((estado = 'COMPLETADO') = (recomendacion IS NOT NULL))
);

CREATE INDEX analisis_evidencias_idx ON analisis_evidencias (autorizacion_id, solicitado_en DESC);

COMMENT ON TABLE analisis_evidencias IS 'CCPIP-50: analisis preliminar asistido. Sugerencia para la revision humana; la decision esta en autorizacion_decision.';
COMMENT ON COLUMN analisis_evidencias.simulado IS 'Verdadero si lo genero el proveedor simulado de pruebas y demo: no es un analisis real.';
COMMENT ON COLUMN analisis_evidencias.evidencias IS 'Evidencias vigentes que se analizaron: si el productor sube otra despues, el analisis queda desactualizado.';

CREATE TABLE analisis_hallazgo (
    id           bigserial    PRIMARY KEY,
    analisis_id  bigint       NOT NULL REFERENCES analisis_evidencias(id),
    evidencia_id uuid         REFERENCES evidencia_producto(id),
    tipo         varchar(14)  NOT NULL,
    detalle      varchar(500) NOT NULL,
    CONSTRAINT hallazgo_tipo_valido CHECK (tipo IN ('FALTANTE', 'INCONSISTENCIA', 'CONFORME'))
);

CREATE INDEX analisis_hallazgo_idx ON analisis_hallazgo (analisis_id);

COMMENT ON COLUMN analisis_hallazgo.evidencia_id IS 'El certificado o documento al que se refiere. Nula si es una evidencia que falta.';

-- La decision cita el analisis que se tuvo a la vista, si hubo uno
ALTER TABLE autorizacion_decision ADD COLUMN analisis_id bigint REFERENCES analisis_evidencias(id);

COMMENT ON COLUMN autorizacion_decision.analisis_id IS 'CCPIP-50: analisis completado que acompano la decision. Nulo si se reviso a mano.';

CREATE TRIGGER analisis_evidencias_sin_update BEFORE UPDATE ON analisis_evidencias
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER analisis_evidencias_sin_delete BEFORE DELETE ON analisis_evidencias
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER analisis_hallazgo_sin_update BEFORE UPDATE ON analisis_hallazgo
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER analisis_hallazgo_sin_delete BEFORE DELETE ON analisis_hallazgo
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
