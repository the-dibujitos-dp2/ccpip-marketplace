-- CCPIP-39 (RF-16): el subadministrador revoca la autorizacion de venta de un producto, con motivo.
-- CCPIP-330 (RF-220): revoca la venta de un productor completo, con motivo.
-- CCPIP-291 (RF-147): la Camara habilita la tienda de un productor autorizado; el productor no la
-- vuelve operativa por su cuenta.
-- CCPIP-292 (RF-148): la asociacion productor-producto guarda las lineas que se autorizaron; cambiar
-- la linea del producto no extiende el permiso.
--
-- Nada se borra: revocar cambia el estado vigente y deja una fila nueva, de solo insercion, en el
-- historial. Los pedidos ya confirmados no se tocan; la regla de compra (CatalogoRepository) deja de
-- habilitar la oferta al instante y el pago de un pedido pendiente la vuelve a controlar.

-- ---------------------------------------------------------------------------------------------
-- Revocar un producto (CCPIP-39): otra decision del mismo historial
-- ---------------------------------------------------------------------------------------------

ALTER TABLE autorizacion_decision DROP CONSTRAINT decision_valida;
ALTER TABLE autorizacion_decision ADD CONSTRAINT decision_valida CHECK (decision IN ('APROBADA', 'RECHAZADA', 'REVOCADA'));

COMMENT ON TABLE autorizacion_decision IS 'CCPIP-174, CCPIP-284 y CCPIP-39: auditoria de cada aprobacion, rechazo o revocacion. Solo insercion.';

-- ---------------------------------------------------------------------------------------------
-- Lineas autorizadas (CCPIP-292)
-- ---------------------------------------------------------------------------------------------

ALTER TABLE autorizacion_producto ADD COLUMN lineas smallint[];

COMMENT ON COLUMN autorizacion_producto.lineas IS 'CCPIP-292: lineas del producto al aprobarse. Si despues se le agrega otra linea, la oferta queda en Venta pausada hasta una nueva revision.';

-- Lo ya aprobado queda autorizado con las lineas que tiene hoy
UPDATE autorizacion_producto a
SET lineas = coalesce((SELECT array_agg(pl.linea_id ORDER BY pl.linea_id) FROM producto_linea pl
                       WHERE pl.producto_id = a.producto_id), '{}')
WHERE a.estado = 'APROBADA';

-- Al pasar a APROBADA (por la bandeja, la semilla o una prueba) se fijan las lineas del momento.
-- Asi ninguna aprobacion queda sin alcance y nadie tiene que acordarse de copiarlas.
CREATE OR REPLACE FUNCTION autorizacion_fija_lineas()
RETURNS trigger AS $$
BEGIN
    IF NEW.estado = 'APROBADA' AND (TG_OP = 'INSERT' OR OLD.estado <> 'APROBADA' OR NEW.lineas IS NULL) THEN
        NEW.lineas := coalesce((SELECT array_agg(pl.linea_id ORDER BY pl.linea_id) FROM producto_linea pl
                                WHERE pl.producto_id = NEW.producto_id), '{}');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER autorizacion_producto_lineas BEFORE INSERT OR UPDATE ON autorizacion_producto
    FOR EACH ROW EXECUTE FUNCTION autorizacion_fija_lineas();

ALTER TABLE autorizacion_producto
    ADD CONSTRAINT autorizacion_aprobada_con_lineas CHECK (estado <> 'APROBADA' OR lineas IS NOT NULL);

-- ---------------------------------------------------------------------------------------------
-- Decisiones sobre el productor (CCPIP-291, CCPIP-330)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE productor_decision (
    id              bigserial    PRIMARY KEY,
    productor_id    bigint       NOT NULL REFERENCES productor(id),
    decision        varchar(16)  NOT NULL,
    motivo          varchar(500) NOT NULL,
    autorizaciones  bigint[]     NOT NULL DEFAULT '{}',
    decidido_por    bigint       NOT NULL REFERENCES usuario(id),
    decidido_en     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT productor_decision_valida CHECK (decision IN ('HABILITAR_TIENDA', 'REVOCAR_VENTA', 'RESTITUIR_VENTA')),
    CONSTRAINT productor_decision_motivo_presente CHECK (btrim(motivo) <> '')
);

CREATE INDEX productor_decision_idx ON productor_decision (productor_id, decidido_en DESC);

COMMENT ON TABLE productor_decision IS 'CCPIP-291 y CCPIP-330: habilitacion de la tienda y revocacion o restitucion de la venta del productor, con motivo y responsable. Solo insercion.';
COMMENT ON COLUMN productor_decision.autorizaciones IS 'Autorizaciones APROBADAS del productor al decidir: el alcance de una revocacion.';

CREATE TRIGGER productor_decision_sin_update BEFORE UPDATE ON productor_decision
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER productor_decision_sin_delete BEFORE DELETE ON productor_decision
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();

ALTER TABLE productor
    ADD COLUMN tienda_habilitada    boolean NOT NULL DEFAULT false,
    ADD COLUMN tienda_decision_id   bigint  REFERENCES productor_decision(id),
    ADD COLUMN venta_revocada       boolean NOT NULL DEFAULT false,
    ADD COLUMN venta_decision_id    bigint  REFERENCES productor_decision(id);

COMMENT ON COLUMN productor.tienda_habilitada IS 'CCPIP-291: la Camara habilito la tienda. Solo una tienda habilitada se puede publicar.';
COMMENT ON COLUMN productor.tienda_decision_id IS 'Decision que habilito la tienda. Nula en las habilitadas como datos iniciales (migracion y semilla).';
COMMENT ON COLUMN productor.venta_revocada IS 'CCPIP-330: la venta del productor esta revocada. Sus ofertas siguen visibles en Venta pausada.';
COMMENT ON COLUMN productor.venta_decision_id IS 'Ultima revocacion o restitucion de la venta del productor.';

-- Datos iniciales: las tiendas que ya estaban publicadas quedan habilitadas, para no sacarlas del
-- catalogo de golpe. Es la habilitacion manual documentada de RF-147.
UPDATE productor SET tienda_habilitada = true WHERE tienda_publicada;

ALTER TABLE productor
    ADD CONSTRAINT productor_tienda_publicada_habilitada CHECK (NOT tienda_publicada OR tienda_habilitada);
