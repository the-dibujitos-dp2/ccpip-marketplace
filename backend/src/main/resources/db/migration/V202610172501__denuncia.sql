-- CCPIP-83 (RF-90): el comprador denuncia un producto o un productor con motivo y descripcion, sin
-- aprobacion previa. CCPIP-84 (RF-77): el subadministrador consulta las denuncias por productor y su
-- historial. CCPIP-116 (RF-91): el productor ve las denuncias sobre sus ofertas, anonimizadas.
-- CCPIP-323 (RF-216): el productor responde una denuncia.
--
-- La identidad del denunciante (denunciante_id) solo la usa el servidor: ninguna respuesta de la API la
-- expone, ni al productor ni al subadministrador (RNF-41). Una denuncia nunca se borra: cambia de estado
-- y cada paso queda en denuncia_evento, que es de solo insercion.

CREATE TABLE denuncia (
    id                  bigserial     PRIMARY KEY,
    denunciante_id      bigint        NOT NULL REFERENCES usuario(id),
    productor_id        bigint        NOT NULL REFERENCES productor(id),
    producto_id         bigint,
    motivo              varchar(30)   NOT NULL,
    descripcion         varchar(1000) NOT NULL,
    estado              varchar(20)   NOT NULL DEFAULT 'RECIBIDA',
    respuesta_productor varchar(1000),
    respondida_en       timestamptz,
    creada_en           timestamptz   NOT NULL DEFAULT now(),
    actualizada_en      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT denuncia_motivo_valido CHECK (motivo IN (
        'NO_CORRESPONDE', 'CALIDAD', 'ORIGEN_DUDOSO', 'PRACTICA_INDEBIDA', 'CONTENIDO_INAPROPIADO', 'OTRO')),
    CONSTRAINT denuncia_estado_valido CHECK (estado IN ('RECIBIDA', 'EN_REVISION', 'RESUELTA', 'DESESTIMADA')),
    CONSTRAINT denuncia_descripcion_no_vacia CHECK (btrim(descripcion) <> ''),
    CONSTRAINT denuncia_respuesta_completa CHECK ((respuesta_productor IS NULL) = (respondida_en IS NULL)),
    -- Denuncia de un producto: el productor es el de esa oferta (CCPIP-172)
    CONSTRAINT denuncia_oferta_fk FOREIGN KEY (producto_id, productor_id) REFERENCES producto (id, productor_id)
);

CREATE INDEX denuncia_productor_idx ON denuncia (productor_id, creada_en DESC);
CREATE INDEX denuncia_denunciante_idx ON denuncia (denunciante_id);
CREATE INDEX denuncia_estado_idx ON denuncia (estado);

COMMENT ON TABLE denuncia IS 'CCPIP-83: denuncia de un comprador sobre un producto (producto_id) o sobre el productor (producto_id nulo). Nunca se borra.';
COMMENT ON COLUMN denuncia.denunciante_id IS 'RNF-41: identidad protegida. No sale en ninguna respuesta; sirve para el limite de duplicados y la trazabilidad interna.';
COMMENT ON COLUMN denuncia.estado IS 'RECIBIDA al registrarse; el subadministrador la pasa a EN_REVISION y la cierra como RESUELTA o DESESTIMADA.';
COMMENT ON COLUMN denuncia.respuesta_productor IS 'CCPIP-323: descargo del productor, una sola vez por denuncia. Lo ven el subadministrador y el comprador.';

CREATE TABLE denuncia_evento (
    id              bigserial     PRIMARY KEY,
    denuncia_id     bigint        NOT NULL REFERENCES denuncia(id),
    tipo            varchar(20)   NOT NULL,
    estado          varchar(20)   NOT NULL,
    nota            varchar(1000),
    usuario_id      bigint        NOT NULL REFERENCES usuario(id),
    rol             varchar(20)   NOT NULL,
    registrado_en   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT denuncia_evento_tipo_valido CHECK (tipo IN ('REGISTRADA', 'RESPUESTA', 'CAMBIO_ESTADO')),
    CONSTRAINT denuncia_evento_estado_valido CHECK (estado IN ('RECIBIDA', 'EN_REVISION', 'RESUELTA', 'DESESTIMADA')),
    CONSTRAINT denuncia_evento_rol_valido CHECK (rol IN ('CONSUMIDOR', 'PRODUCTOR', 'SUB_ADMIN'))
);

CREATE INDEX denuncia_evento_idx ON denuncia_evento (denuncia_id, registrado_en, id);

COMMENT ON TABLE denuncia_evento IS 'Historial y auditoria de cada denuncia: registro, respuesta del productor y cada cambio de estado con quien lo hizo. Solo insercion.';
COMMENT ON COLUMN denuncia_evento.estado IS 'Estado de la denuncia despues del evento.';
COMMENT ON COLUMN denuncia_evento.usuario_id IS 'Quien hizo el paso. En el evento REGISTRADA es el denunciante: nunca se expone.';

CREATE TRIGGER denuncia_evento_sin_update BEFORE UPDATE ON denuncia_evento
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER denuncia_evento_sin_delete BEFORE DELETE ON denuncia_evento
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER denuncia_sin_delete BEFORE DELETE ON denuncia
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();

-- El denunciante, el productor y la oferta no cambian despues de registrar
CREATE OR REPLACE FUNCTION denuncia_conservar_origen()
RETURNS trigger AS $$
BEGIN
    IF NEW.denunciante_id IS DISTINCT FROM OLD.denunciante_id OR NEW.productor_id IS DISTINCT FROM OLD.productor_id
       OR NEW.producto_id IS DISTINCT FROM OLD.producto_id OR NEW.motivo IS DISTINCT FROM OLD.motivo
       OR NEW.descripcion IS DISTINCT FROM OLD.descripcion OR NEW.creada_en IS DISTINCT FROM OLD.creada_en THEN
        RAISE EXCEPTION 'Lo denunciado no se modifica; solo cambian el estado y la respuesta (CCPIP-83).'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.respuesta_productor IS NOT NULL AND NEW.respuesta_productor IS DISTINCT FROM OLD.respuesta_productor THEN
        RAISE EXCEPTION 'La respuesta del productor no se reemplaza (CCPIP-323).' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER denuncia_origen_fijo BEFORE UPDATE ON denuncia
    FOR EACH ROW EXECUTE FUNCTION denuncia_conservar_origen();
