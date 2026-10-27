-- CCPIP-43 (RF-29): el productor registra un premio vinculado a un producto y a uno de sus lotes, con la
-- entidad que lo otorgo, la fecha, la vigencia y el archivo que lo respalda. CCPIP-169 (RF-138): el
-- visitante ve los premios del producto con su emisor, fecha y vigencia, nunca el archivo (RNF-08).
--
-- Acordado el 26/10: el premio es publico al registrarlo, como "declarado por el productor, con
-- evidencia". No pasa por una revision de la Camara y no se presenta como aprobado por ella.
--
-- Va en su propia tabla y no como una evidencia de la autorizacion (evidencia_producto): agregar una
-- evidencia reabre la solicitud de autorizacion del producto y pausaria su venta.

-- El lote del premio es del mismo producto: llave foranea compuesta
ALTER TABLE lote ADD CONSTRAINT lote_de_su_producto UNIQUE (id, producto_id);

CREATE TABLE premio_producto (
    id              bigserial    PRIMARY KEY,
    productor_id    bigint       NOT NULL REFERENCES productor (id),
    producto_id     bigint       NOT NULL REFERENCES producto (id),
    lote_id         bigint       NOT NULL,
    titulo          varchar(160) NOT NULL,
    emisor          varchar(160) NOT NULL,
    otorgado_en     date         NOT NULL,
    vigente_hasta   date,
    nombre_archivo  varchar(200) NOT NULL,
    content_type    varchar(40)  NOT NULL,
    bytes           integer      NOT NULL,
    clave           varchar(200) NOT NULL UNIQUE,
    registrado_por  bigint       NOT NULL REFERENCES usuario (id),
    registrado_en   timestamptz  NOT NULL DEFAULT now(),
    retirado_por    bigint       REFERENCES usuario (id),
    retirado_en     timestamptz,
    CONSTRAINT premio_lote_del_producto FOREIGN KEY (lote_id, producto_id) REFERENCES lote (id, producto_id),
    CONSTRAINT premio_titulo_presente CHECK (btrim(titulo) <> ''),
    CONSTRAINT premio_emisor_presente CHECK (btrim(emisor) <> ''),
    CONSTRAINT premio_vigencia_ordenada CHECK (vigente_hasta IS NULL OR vigente_hasta >= otorgado_en),
    CONSTRAINT premio_formato_valido CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    CONSTRAINT premio_retiro_completo CHECK ((retirado_en IS NULL) = (retirado_por IS NULL))
);

CREATE INDEX premio_producto_vigente_idx ON premio_producto (producto_id, otorgado_en DESC) WHERE retirado_en IS NULL;

COMMENT ON TABLE premio_producto IS 'CCPIP-43: premios de un producto y su lote, declarados por el productor con su evidencia. Publicos sin el archivo (CCPIP-169).';
COMMENT ON COLUMN premio_producto.vigente_hasta IS 'Ultimo dia en que el premio vale (hora de Lima). Nula si el premio no vence.';
COMMENT ON COLUMN premio_producto.clave IS 'Archivo de respaldo en el bucket privado. Solo lo ven el productor y la administracion (RNF-08).';
COMMENT ON COLUMN premio_producto.retirado_en IS 'Retiro logico: deja de mostrarse, pero se conserva.';

-- El premio no se borra ni se reescribe: solo se retira una vez
CREATE FUNCTION premio_producto_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Un premio no se borra; se retira (CCPIP-43).';
    END IF;
    IF OLD.retirado_en IS NOT NULL
       OR (NEW.productor_id, NEW.producto_id, NEW.lote_id, NEW.titulo, NEW.emisor, NEW.otorgado_en, NEW.vigente_hasta,
           NEW.nombre_archivo, NEW.content_type, NEW.bytes, NEW.clave, NEW.registrado_por, NEW.registrado_en)
          IS DISTINCT FROM
          (OLD.productor_id, OLD.producto_id, OLD.lote_id, OLD.titulo, OLD.emisor, OLD.otorgado_en, OLD.vigente_hasta,
           OLD.nombre_archivo, OLD.content_type, OLD.bytes, OLD.clave, OLD.registrado_por, OLD.registrado_en) THEN
        RAISE EXCEPTION 'Un premio solo admite su retiro, una vez (CCPIP-43).';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER premio_producto_sin_cambios
    BEFORE UPDATE OR DELETE ON premio_producto
    FOR EACH ROW EXECUTE FUNCTION premio_producto_inmutable();

-- El historial del producto (CCPIP-47) registra el alta y el retiro de cada premio
ALTER TABLE producto_auditoria DROP CONSTRAINT auditoria_accion_valida;
ALTER TABLE producto_auditoria ADD CONSTRAINT auditoria_accion_valida CHECK (accion IN (
    'CREAR', 'EDITAR', 'PUBLICAR', 'RETIRAR', 'REPUBLICAR', 'HISTORIA', 'LOTE', 'IMAGEN',
    'EVIDENCIA', 'AUTORIZACION', 'MODERACION', 'PREMIO'));
