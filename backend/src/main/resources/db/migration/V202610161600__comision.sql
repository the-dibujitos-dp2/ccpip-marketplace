-- CCPIP-90 (RF-68): la comision de la Camara deja de ser una propiedad fija y la configura el
-- superadministrador, con fecha de vigencia, motivo y quien la registro.
-- CCPIP-91 (RF-69) y CCPIP-167 (RF-136): cada pedido guarda, al confirmarse, el porcentaje vigente,
-- la base y los montos calculados. Cambios posteriores de la comision no los alteran (RNF-13).
--
-- No se siembra ningun porcentaje aqui: el valor lo define la Camara ("sin porcentaje inventado").
-- El 10 % de la demo va en db/seed/R__04_comision_demo.sql.

CREATE TABLE comision_configuracion (
    id             bigserial     PRIMARY KEY,
    porcentaje     numeric(5,2)  NOT NULL,
    base_calculo   varchar(30)   NOT NULL DEFAULT 'PRECIO_SIN_ENVIO',
    vigente_desde  date          NOT NULL,
    motivo         varchar(300)  NOT NULL,
    registrado_por bigint        NOT NULL REFERENCES usuario(id),
    registrado_en  timestamptz   NOT NULL DEFAULT now(),
    cancelado_por  bigint        REFERENCES usuario(id),
    cancelado_en   timestamptz,
    CONSTRAINT comision_porcentaje_rango CHECK (porcentaje >= 0 AND porcentaje < 100),
    -- Unica base mientras CCPIP-101 no defina el flete: el precio de venta sin costo de envio
    CONSTRAINT comision_base_valida CHECK (base_calculo IN ('PRECIO_SIN_ENVIO')),
    CONSTRAINT comision_motivo_presente CHECK (btrim(motivo) <> ''),
    CONSTRAINT comision_cancelacion_completa CHECK ((cancelado_por IS NULL) = (cancelado_en IS NULL))
);

-- Un solo cambio por dia de vigencia; los cancelados quedan en el historial sin ocupar la fecha
CREATE UNIQUE INDEX comision_configuracion_fecha_unica ON comision_configuracion (vigente_desde)
    WHERE cancelado_en IS NULL;

COMMENT ON TABLE comision_configuracion IS
    'CCPIP-90: historial auditado de la comision. Rige la fila no cancelada con la mayor vigente_desde <= hoy (Lima).';

-- Es la auditoria del cambio: no se borra ni se edita. Lo unico permitido es cancelar un cambio
-- programado (llenar cancelado_por y cancelado_en una sola vez).
CREATE OR REPLACE FUNCTION comision_configuracion_inmutable()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'comision_configuracion no se borra (CCPIP-90).';
    END IF;
    IF OLD.cancelado_en IS NOT NULL
       OR NEW.cancelado_en IS NULL
       OR (NEW.porcentaje, NEW.base_calculo, NEW.vigente_desde, NEW.motivo, NEW.registrado_por, NEW.registrado_en)
          IS DISTINCT FROM
          (OLD.porcentaje, OLD.base_calculo, OLD.vigente_desde, OLD.motivo, OLD.registrado_por, OLD.registrado_en) THEN
        RAISE EXCEPTION 'comision_configuracion solo admite cancelar un cambio programado (CCPIP-90).';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER comision_configuracion_sin_update BEFORE UPDATE ON comision_configuracion
    FOR EACH ROW EXECUTE FUNCTION comision_configuracion_inmutable();
CREATE TRIGGER comision_configuracion_sin_delete BEFORE DELETE ON comision_configuracion
    FOR EACH ROW EXECUTE FUNCTION comision_configuracion_inmutable();

-- Copia de la configuracion en el pedido. Nula si se confirmo sin comision configurada o antes de
-- esta historia: no se recalcula con un porcentaje que no regia entonces.
ALTER TABLE pedido ADD COLUMN comision_configuracion_id bigint REFERENCES comision_configuracion(id);
ALTER TABLE pedido ADD COLUMN comision_porcentaje       numeric(5,2);
ALTER TABLE pedido ADD COLUMN comision_base             varchar(30);
ALTER TABLE pedido ADD COLUMN comision_monto            numeric(12,2);

ALTER TABLE pedido ADD CONSTRAINT pedido_comision_completa CHECK (
    (comision_porcentaje IS NULL) = (comision_configuracion_id IS NULL)
    AND (comision_porcentaje IS NULL) = (comision_base IS NULL)
    AND (comision_porcentaje IS NULL) = (comision_monto IS NULL));
ALTER TABLE pedido ADD CONSTRAINT pedido_comision_al_confirmar
    CHECK (comision_porcentaje IS NULL OR confirmado_en IS NOT NULL);
ALTER TABLE pedido ADD CONSTRAINT pedido_comision_no_negativa CHECK (comision_monto IS NULL OR comision_monto >= 0);

-- Por linea, para liquidar a cada productor (PW-11): comision y neto que recibe
ALTER TABLE item_pedido ADD COLUMN comision_monto numeric(12,2);
ALTER TABLE item_pedido ADD COLUMN neto           numeric(12,2);

ALTER TABLE item_pedido ADD CONSTRAINT item_pedido_comision_completa CHECK ((comision_monto IS NULL) = (neto IS NULL));
ALTER TABLE item_pedido ADD CONSTRAINT item_pedido_neto_cuadra
    CHECK (comision_monto IS NULL OR (comision_monto >= 0 AND neto = subtotal - comision_monto));

COMMENT ON COLUMN pedido.comision_porcentaje IS 'CCPIP-91/167: porcentaje vigente al confirmar. Inmutable una vez copiado.';
COMMENT ON COLUMN pedido.comision_monto IS 'Suma de item_pedido.comision_monto: se redondea una sola vez, por linea (HALF_UP, 2 decimales).';
COMMENT ON COLUMN item_pedido.neto IS 'Lo que recibe el productor por la linea: subtotal - comision_monto.';

-- RNF-13: los importes y la comision copiados al confirmar no cambian despues
CREATE OR REPLACE FUNCTION pedido_comision_inmutable()
RETURNS trigger AS $$
BEGIN
    IF OLD.comision_porcentaje IS NOT NULL
       AND (NEW.comision_configuracion_id, NEW.comision_porcentaje, NEW.comision_base, NEW.comision_monto, NEW.total)
           IS DISTINCT FROM
           (OLD.comision_configuracion_id, OLD.comision_porcentaje, OLD.comision_base, OLD.comision_monto, OLD.total) THEN
        RAISE EXCEPTION 'Los importes y la comision de un pedido confirmado no cambian (CCPIP-167).';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pedido_comision_congelada BEFORE UPDATE ON pedido
    FOR EACH ROW EXECUTE FUNCTION pedido_comision_inmutable();

CREATE OR REPLACE FUNCTION item_pedido_comision_inmutable()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.comision_monto IS NOT NULL THEN
            RAISE EXCEPTION 'Las lineas de un pedido confirmado no se borran (CCPIP-167).';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.comision_monto IS NOT NULL
       AND (NEW.cantidad, NEW.precio_unitario, NEW.subtotal, NEW.comision_monto, NEW.neto)
           IS DISTINCT FROM
           (OLD.cantidad, OLD.precio_unitario, OLD.subtotal, OLD.comision_monto, OLD.neto) THEN
        RAISE EXCEPTION 'Los importes y la comision de un pedido confirmado no cambian (CCPIP-167).';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER item_pedido_comision_congelada BEFORE UPDATE OR DELETE ON item_pedido
    FOR EACH ROW EXECUTE FUNCTION item_pedido_comision_inmutable();
