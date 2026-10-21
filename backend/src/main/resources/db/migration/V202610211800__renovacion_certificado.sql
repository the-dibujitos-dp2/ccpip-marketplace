-- CCPIP-321 (RF-205): el subadministrador registra una solicitud de renovacion de un certificado por
-- producto. Es un aviso al productor y un registro para la revision; no cambia la autorizacion. Lo que
-- pasa con la venta lo sigue decidiendo C5: el certificado vencido pausa la venta (el estado sigue
-- APROBADA y no hay decision), nunca la bloquea para siempre. Por eso esta tabla no toca
-- autorizacion_producto ni autorizacion_decision.

CREATE TABLE renovacion_certificado (
    id              bigserial PRIMARY KEY,
    autorizacion_id bigint       NOT NULL,
    productor_id    bigint       NOT NULL,
    producto_id     bigint       NOT NULL,
    evidencia_id    uuid         NOT NULL REFERENCES evidencia_producto (id),
    motivo          varchar(500) NOT NULL,
    plazo           date         NOT NULL,
    estado          varchar(10)  NOT NULL DEFAULT 'PENDIENTE',
    solicitada_por  bigint       NOT NULL REFERENCES usuario (id),
    solicitada_en   timestamptz  NOT NULL DEFAULT now(),
    atendida_en     timestamptz,
    -- El certificado es de la misma autorizacion: no se pide renovar el de otro producto
    CONSTRAINT renovacion_de_su_autorizacion FOREIGN KEY (autorizacion_id, productor_id, producto_id)
        REFERENCES autorizacion_producto (id, productor_id, producto_id),
    CONSTRAINT renovacion_estado_valido CHECK (estado IN ('PENDIENTE', 'ATENDIDA', 'CANCELADA')),
    CONSTRAINT renovacion_motivo_presente CHECK (btrim(motivo) <> ''),
    CONSTRAINT renovacion_atencion_coherente CHECK ((estado = 'PENDIENTE') = (atendida_en IS NULL))
);

-- Una sola solicitud pendiente por certificado
CREATE UNIQUE INDEX renovacion_una_pendiente_idx ON renovacion_certificado (evidencia_id) WHERE estado = 'PENDIENTE';
CREATE INDEX renovacion_autorizacion_idx ON renovacion_certificado (autorizacion_id, solicitada_en DESC);

COMMENT ON TABLE renovacion_certificado IS
    'CCPIP-321: solicitudes de renovacion de un certificado de la autorizacion de un producto. No cambian la autorizacion ni la venta (C5).';
COMMENT ON COLUMN renovacion_certificado.plazo IS
    'Hasta cuando se espera el certificado renovado. Por defecto, el vencimiento del certificado (o hoy si ya vencio).';

-- La solicitud no se borra ni se reescribe: solo pasa una vez de PENDIENTE a ATENDIDA o CANCELADA
CREATE FUNCTION renovacion_certificado_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Una solicitud de renovacion no se borra (CCPIP-321).';
    END IF;
    IF OLD.estado <> 'PENDIENTE'
       OR (NEW.autorizacion_id, NEW.evidencia_id, NEW.motivo, NEW.plazo, NEW.solicitada_por, NEW.solicitada_en)
          IS DISTINCT FROM
          (OLD.autorizacion_id, OLD.evidencia_id, OLD.motivo, OLD.plazo, OLD.solicitada_por, OLD.solicitada_en) THEN
        RAISE EXCEPTION 'Una solicitud de renovacion solo cambia de PENDIENTE a ATENDIDA o CANCELADA (CCPIP-321).';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER renovacion_certificado_sin_cambios
    BEFORE UPDATE OR DELETE ON renovacion_certificado
    FOR EACH ROW EXECUTE FUNCTION renovacion_certificado_inmutable();

-- El aviso por correo al productor queda registrado como los de las decisiones
ALTER TABLE aviso DROP CONSTRAINT aviso_tipo_valido;
ALTER TABLE aviso ADD CONSTRAINT aviso_tipo_valido CHECK (tipo IN (
    'PEDIDO_LISTO', 'INTERES_VENDIBLE', 'DECISION_AUTORIZACION', 'DECISION_PRODUCTOR', 'RENOVACION_CERTIFICADO'));
