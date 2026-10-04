-- Historia del producto en cuatro respuestas, preparada para la redaccion asistida por IA
-- (CCPIP-48 y CCPIP-49, PW-05), y registro de cambios de cada producto (CCPIP-47).

CREATE TABLE producto_historia (
    producto_id     bigint       PRIMARY KEY REFERENCES producto(id),
    quienes         text,
    como            text,
    significado     text,
    cuidado         text,
    titulo          varchar(160),
    texto_propuesto text,
    texto_final     text,
    asistida        boolean      NOT NULL DEFAULT false,
    proveedor_ia    varchar(40),
    aprobada_en     timestamptz,
    aprobada_por    bigint       REFERENCES usuario(id),
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT historia_aprobada_tiene_texto CHECK (aprobada_en IS NULL OR texto_final IS NOT NULL)
);

COMMENT ON TABLE producto_historia IS 'CCPIP-49: se conserva la version propuesta por la IA (texto_propuesto) y la final aprobada (texto_final). Solo la aprobada se publica, copiada a producto.historia_origen.';
COMMENT ON COLUMN producto_historia.asistida IS 'RNF-29: si el texto final partio de una propuesta de IA. La ficha lo indica como relato revisado por la comunidad.';

-- Las historias ya sembradas cuentan como escritas y aprobadas por la comunidad
INSERT INTO producto_historia (producto_id, texto_final, aprobada_en)
SELECT id, historia_origen, now() FROM producto WHERE historia_origen IS NOT NULL AND btrim(historia_origen) <> '';

CREATE TABLE producto_auditoria (
    id            bigserial    PRIMARY KEY,
    producto_id   bigint       NOT NULL REFERENCES producto(id),
    usuario_id    bigint       REFERENCES usuario(id),
    accion        varchar(20)  NOT NULL,
    cambios       jsonb,
    registrado_en timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT auditoria_accion_valida CHECK (accion IN (
        'CREAR', 'EDITAR', 'PUBLICAR', 'RETIRAR', 'REPUBLICAR', 'HISTORIA', 'LOTE', 'IMAGEN'))
);

CREATE INDEX producto_auditoria_producto_idx ON producto_auditoria (producto_id, registrado_en DESC);

COMMENT ON TABLE producto_auditoria IS 'CCPIP-47: quien cambio que y cuando. Solo insercion, igual que movimiento_inventario.';

-- Igual que los movimientos: una auditoria que se puede editar no sirve como auditoria
CREATE OR REPLACE FUNCTION auditoria_solo_insercion()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'producto_auditoria es de solo insercion (CCPIP-47).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER auditoria_sin_update BEFORE UPDATE ON producto_auditoria
    FOR EACH ROW EXECUTE FUNCTION auditoria_solo_insercion();
CREATE TRIGGER auditoria_sin_delete BEFORE DELETE ON producto_auditoria
    FOR EACH ROW EXECUTE FUNCTION auditoria_solo_insercion();
