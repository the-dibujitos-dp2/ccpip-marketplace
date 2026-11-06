-- CCPIP-485 y CCPIP-486: un producto de un pedido confirmado se malogra en el punto antes de entregarse.
-- Decision del cliente del 05/11/2026 (origen CCPIP-418): primero se reemplaza con otro lote del mismo
-- producto en el mismo punto (485); si no hay, se entrega lo disponible y se reembolsa lo faltante (486).
--
-- Las unidades malogradas ya habian salido de su lote con la venta (SALIDA_VENTA, CCPIP-158). Para que
-- su merma no las descuente dos veces, se registran juntos un REINTEGRO (ENTRADA con motivo
-- REINTEGRO_PEDIDO: vuelven del pedido al lote) y la MERMA del mismo lote: el saldo del lote original no
-- cambia y no se edita (RNF-15, RNF-16), y cada saldo sigue explicado por sus movimientos.
-- En el reemplazo, el lote nuevo se descuenta una sola vez con su propio SALIDA_VENTA.
--
-- item_pedido y pedido no cambian (RNF-13, CCPIP-167): lo faltante, lo reembolsado y la comision anulada
-- van en incidencia_pedido y reembolso, y quien liquida o muestra el pedido los descuenta.

-- ---------------------------------------------------------------------------------------------
-- El reintegro de un pedido a su lote
-- ---------------------------------------------------------------------------------------------

ALTER TABLE movimiento_inventario DROP CONSTRAINT movimiento_motivo_valido;
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_motivo_valido CHECK (
    motivo IS NULL
 OR (tipo = 'ENTRADA' AND motivo IN ('RECEPCION', 'PRODUCCION', 'REINTEGRO_PEDIDO'))
 OR (tipo = 'SALIDA'  AND motivo IN ('DEVOLUCION_PRODUCTOR', 'RETIRO_PRODUCTOR'))
);

-- Un reintegro dice de que pedido, que linea y que lote vuelve, y quien lo registro
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_reintegro_completo CHECK (
    motivo IS DISTINCT FROM 'REINTEGRO_PEDIDO'
    OR (pedido_id IS NOT NULL AND item_pedido_id IS NOT NULL AND lote_id IS NOT NULL AND usuario_id IS NOT NULL)
);

COMMENT ON COLUMN movimiento_inventario.motivo IS 'CCPIP-121, 287, 283: RECEPCION o PRODUCCION en una ENTRADA manual; DEVOLUCION_PRODUCTOR o RETIRO_PRODUCTOR en una SALIDA. CCPIP-485/486: REINTEGRO_PEDIDO, unidades de un pedido que vuelven a su lote para darse de baja como merma. Nulo en los demas movimientos automaticos.';

-- ---------------------------------------------------------------------------------------------
-- reembolso: devolucion parcial (o total) de un pago aprobado
-- ---------------------------------------------------------------------------------------------

CREATE TABLE reembolso (
    id                  bigserial     PRIMARY KEY,
    pago_id             bigint        NOT NULL REFERENCES pago(id),
    pedido_id           bigint        NOT NULL REFERENCES pedido(id),
    monto               numeric(12,2) NOT NULL,
    pasarela            varchar(40)   NOT NULL,
    referencia_pasarela varchar(100)  NOT NULL,
    registrado_por      bigint        NOT NULL REFERENCES usuario(id),
    registrado_en       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT reembolso_monto_positivo CHECK (monto > 0)
);

CREATE INDEX reembolso_pedido_idx ON reembolso (pedido_id);

COMMENT ON TABLE reembolso IS 'CCPIP-486: lo devuelto al comprador de un pago aprobado, sin tocar el pago original. Solo insercion; la suma nunca supera lo cobrado.';

-- Solo de un pago aprobado de ese pedido, y sin devolver mas de lo cobrado
CREATE FUNCTION reembolso_al_crear() RETURNS trigger AS $$
DECLARE
    cobrado   numeric(12,2);
    devuelto  numeric(12,2);
BEGIN
    SELECT monto INTO cobrado FROM pago WHERE id = NEW.pago_id AND pedido_id = NEW.pedido_id AND estado = 'APROBADO' FOR UPDATE;
    IF cobrado IS NULL THEN
        RAISE EXCEPTION 'Solo se reembolsa un pago aprobado de ese pedido (CCPIP-486).';
    END IF;
    SELECT coalesce(sum(monto), 0) INTO devuelto FROM reembolso WHERE pago_id = NEW.pago_id;
    IF devuelto + NEW.monto > cobrado THEN
        RAISE EXCEPTION 'El reembolso (%) supera lo que queda del pago (%).', NEW.monto, cobrado - devuelto;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reembolso_validar BEFORE INSERT ON reembolso
    FOR EACH ROW EXECUTE FUNCTION reembolso_al_crear();

-- ---------------------------------------------------------------------------------------------
-- incidencia_pedido: cada producto malogrado de una linea y como se resolvio
-- ---------------------------------------------------------------------------------------------

CREATE TABLE incidencia_pedido (
    id                  bigserial     PRIMARY KEY,
    pedido_id           bigint        NOT NULL REFERENCES pedido(id),
    item_pedido_id      bigint        NOT NULL REFERENCES item_pedido(id),
    tipo                varchar(20)   NOT NULL,
    cantidad            numeric(12,3) NOT NULL,
    lote_original_id    bigint        NOT NULL REFERENCES lote(id),
    -- Solo en un REEMPLAZO: el lote que lo cubre
    lote_nuevo_id       bigint        REFERENCES lote(id),
    -- La merma que dio de baja las unidades malogradas (CCPIP-76)
    merma_movimiento_id bigint        NOT NULL UNIQUE REFERENCES merma(movimiento_id),
    -- Solo en un FALTANTE: lo devuelto por las unidades que no se entregan y la comision que se anula
    monto               numeric(12,2) NOT NULL DEFAULT 0,
    comision_anulada    numeric(12,2) NOT NULL DEFAULT 0,
    reembolso_id        bigint        REFERENCES reembolso(id),
    registrado_por      bigint        NOT NULL REFERENCES usuario(id),
    registrado_en       timestamptz   NOT NULL DEFAULT now(),
    -- Un reintento del mismo formulario no registra dos veces
    clave_idempotencia  uuid          NOT NULL,
    CONSTRAINT incidencia_tipo_valido CHECK (tipo IN ('REEMPLAZO', 'FALTANTE')),
    CONSTRAINT incidencia_cantidad_positiva CHECK (cantidad > 0),
    CONSTRAINT incidencia_montos_no_negativos CHECK (monto >= 0 AND comision_anulada >= 0 AND comision_anulada <= monto),
    CONSTRAINT incidencia_reemplazo_completo CHECK (
        tipo <> 'REEMPLAZO' OR (lote_nuevo_id IS NOT NULL AND lote_nuevo_id <> lote_original_id
                                AND monto = 0 AND comision_anulada = 0 AND reembolso_id IS NULL)),
    CONSTRAINT incidencia_faltante_completo CHECK (tipo <> 'FALTANTE' OR lote_nuevo_id IS NULL),
    CONSTRAINT incidencia_clave_unica UNIQUE (registrado_por, clave_idempotencia)
);

CREATE INDEX incidencia_pedido_idx ON incidencia_pedido (pedido_id, registrado_en);
CREATE INDEX incidencia_item_idx ON incidencia_pedido (item_pedido_id);

COMMENT ON TABLE incidencia_pedido IS 'CCPIP-485/486: un producto malogrado de una linea de pedido antes de entregarse, reemplazado con otro lote (REEMPLAZO) o no entregado y reembolsado (FALTANTE). Solo insercion.';

CREATE FUNCTION incidencia_pedido_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'incidencia_pedido y reembolso son de solo insercion (CCPIP-485/486).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER incidencia_pedido_sin_cambios BEFORE UPDATE OR DELETE ON incidencia_pedido
    FOR EACH ROW EXECUTE FUNCTION incidencia_pedido_solo_insercion();
CREATE TRIGGER reembolso_sin_cambios BEFORE UPDATE OR DELETE ON reembolso
    FOR EACH ROW EXECUTE FUNCTION incidencia_pedido_solo_insercion();

-- Lo que no se entrega de una linea nunca supera lo que se compro
CREATE FUNCTION incidencia_faltante_cabe() RETURNS trigger AS $$
DECLARE
    comprado numeric(12,3);
    faltante numeric(12,3);
BEGIN
    IF NEW.tipo <> 'FALTANTE' THEN
        RETURN NEW;
    END IF;
    SELECT cantidad INTO comprado FROM item_pedido WHERE id = NEW.item_pedido_id AND pedido_id = NEW.pedido_id;
    IF comprado IS NULL THEN
        RAISE EXCEPTION 'La linea no es de ese pedido.';
    END IF;
    SELECT coalesce(sum(cantidad), 0) INTO faltante FROM incidencia_pedido
    WHERE item_pedido_id = NEW.item_pedido_id AND tipo = 'FALTANTE';
    IF faltante + NEW.cantidad > comprado THEN
        RAISE EXCEPTION 'Lo que no se entrega (%) supera lo comprado (%).', faltante + NEW.cantidad, comprado;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER incidencia_faltante_validar BEFORE INSERT ON incidencia_pedido
    FOR EACH ROW EXECUTE FUNCTION incidencia_faltante_cabe();

-- ---------------------------------------------------------------------------------------------
-- Lo que queda de cada linea despues de sus faltantes: lo que se entrega, se cobra y se liquida
-- ---------------------------------------------------------------------------------------------

CREATE VIEW ajuste_item_pedido AS
SELECT i.id AS item_pedido_id,
       coalesce(sum(f.cantidad), 0)         AS cantidad_faltante,
       coalesce(sum(f.monto), 0)            AS monto_reembolsado,
       coalesce(sum(f.comision_anulada), 0) AS comision_anulada
FROM item_pedido i
LEFT JOIN incidencia_pedido f ON f.item_pedido_id = i.id AND f.tipo = 'FALTANTE'
GROUP BY i.id;

COMMENT ON VIEW ajuste_item_pedido IS 'CCPIP-486: por linea, lo no entregado y reembolsado y la comision que se anulo en proporcion.';

-- ---------------------------------------------------------------------------------------------
-- Avisos al comprador
-- ---------------------------------------------------------------------------------------------

ALTER TABLE aviso DROP CONSTRAINT aviso_tipo_valido;
ALTER TABLE aviso ADD CONSTRAINT aviso_tipo_valido CHECK (tipo IN (
    'PEDIDO_LISTO', 'INTERES_VENDIBLE', 'DECISION_AUTORIZACION', 'DECISION_PRODUCTOR', 'RENOVACION_CERTIFICADO',
    'DECISION_MODERACION', 'DECISION_VIDEO', 'PRODUCTO_MALOGRADO'));
