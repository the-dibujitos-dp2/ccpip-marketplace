-- CCPIP-46 (RF-21), CCPIP-331 (RF-221) y CCPIP-332 (RF-222): moderacion de la publicacion de una oferta.
-- Autorizacion y publicacion son procesos distintos (alcance, seccion 2): la autorizacion de venta del
-- producto la decide el subadministrador con sus evidencias (CCPIP-174, CCPIP-284); la publicacion es
-- otra revision. Publicar, o volver a publicar tras un retiro o un rechazo, deja la oferta EN_MODERACION
-- hasta que el subadministrador la aprueba (PUBLICADO) o la rechaza con motivo (vuelve a su estado previo).
-- Editar una oferta ya publicada no la saca del catalogo.

ALTER TABLE producto DROP CONSTRAINT producto_estado_valido;
ALTER TABLE producto ADD CONSTRAINT producto_estado_valido
    CHECK (estado IN ('BORRADOR', 'EN_MODERACION', 'PUBLICADO', 'RETIRADO'));

CREATE TABLE moderacion_publicacion (
    id              bigserial    PRIMARY KEY,
    producto_id     bigint       NOT NULL REFERENCES producto (id),
    productor_id    bigint       NOT NULL REFERENCES productor (id),
    estado_anterior varchar(20)  NOT NULL,
    solicitada_por  bigint       NOT NULL REFERENCES usuario (id),
    solicitada_en   timestamptz  NOT NULL DEFAULT now(),
    decision        varchar(10),
    motivo          varchar(500),
    decidida_por    bigint       REFERENCES usuario (id),
    decidida_en     timestamptz,
    CONSTRAINT moderacion_estado_anterior_valido CHECK (estado_anterior IN ('BORRADOR', 'RETIRADO')),
    CONSTRAINT moderacion_decision_valida CHECK (decision IN ('APROBADA', 'RECHAZADA')),
    -- Toda decision humana lleva autor, fecha y motivo
    CONSTRAINT moderacion_decision_completa CHECK (
        (decision IS NULL AND motivo IS NULL AND decidida_por IS NULL AND decidida_en IS NULL)
        OR (decision IS NOT NULL AND btrim(motivo) <> '' AND decidida_por IS NOT NULL AND decidida_en IS NOT NULL))
);

-- Una sola solicitud pendiente por oferta
CREATE UNIQUE INDEX moderacion_una_pendiente_idx ON moderacion_publicacion (producto_id) WHERE decision IS NULL;
CREATE INDEX moderacion_producto_idx ON moderacion_publicacion (producto_id, solicitada_en DESC);
CREATE INDEX moderacion_decidida_idx ON moderacion_publicacion (decidida_en DESC) WHERE decision IS NOT NULL;

COMMENT ON TABLE moderacion_publicacion IS
    'CCPIP-46, CCPIP-331 y CCPIP-332: cada solicitud de publicacion de una oferta y la decision del subadministrador.';
COMMENT ON COLUMN moderacion_publicacion.estado_anterior IS
    'Estado de la oferta antes de pedir su publicacion: a el vuelve si se rechaza.';

-- La solicitud no se borra ni se reescribe: solo se decide una vez
CREATE FUNCTION moderacion_publicacion_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Una solicitud de publicacion no se borra (CCPIP-46).';
    END IF;
    IF OLD.decision IS NOT NULL
       OR (NEW.producto_id, NEW.productor_id, NEW.estado_anterior, NEW.solicitada_por, NEW.solicitada_en)
          IS DISTINCT FROM
          (OLD.producto_id, OLD.productor_id, OLD.estado_anterior, OLD.solicitada_por, OLD.solicitada_en) THEN
        RAISE EXCEPTION 'Una solicitud de publicacion solo se decide una vez (CCPIP-331, CCPIP-332).';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderacion_publicacion_sin_cambios
    BEFORE UPDATE OR DELETE ON moderacion_publicacion
    FOR EACH ROW EXECUTE FUNCTION moderacion_publicacion_inmutable();

-- El historial del producto (CCPIP-47) registra la solicitud y cada decision
ALTER TABLE producto_auditoria DROP CONSTRAINT auditoria_accion_valida;
ALTER TABLE producto_auditoria ADD CONSTRAINT auditoria_accion_valida CHECK (accion IN (
    'CREAR', 'EDITAR', 'PUBLICAR', 'RETIRAR', 'REPUBLICAR', 'HISTORIA', 'LOTE', 'IMAGEN',
    'EVIDENCIA', 'AUTORIZACION', 'MODERACION'));

-- La decision se avisa al productor por correo, como las de su autorizacion
ALTER TABLE aviso DROP CONSTRAINT aviso_tipo_valido;
ALTER TABLE aviso ADD CONSTRAINT aviso_tipo_valido CHECK (tipo IN (
    'PEDIDO_LISTO', 'INTERES_VENDIBLE', 'DECISION_AUTORIZACION', 'DECISION_PRODUCTOR', 'RENOVACION_CERTIFICADO',
    'DECISION_MODERACION'));
