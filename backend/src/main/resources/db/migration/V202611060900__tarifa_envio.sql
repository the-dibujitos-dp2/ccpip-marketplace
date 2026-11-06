-- CCPIP-482 y CCPIP-483: costo de envio a domicilio. Decision del cliente del 05/11/2026 (CCPIP-101): la
-- Camara fija una tarifa por distrito de destino, el recojo cuesta S/ 0 y la comision se calcula solo sobre
-- los productos (ya es asi: comision_base = PRECIO_SIN_ENVIO). El traslado entre centros lo asume la Camara.
--
-- pedido.total sigue siendo la suma de los productos (base de comision, liquidaciones y reportes de ventas);
-- el envio va aparte en pedido.costo_envio y el comprador paga total + costo_envio.

CREATE TABLE tarifa_envio (
    ubigeo           char(6)       PRIMARY KEY REFERENCES ubigeo(codigo),
    monto            numeric(10,2) NOT NULL,
    actualizado_en   timestamptz   NOT NULL DEFAULT now(),
    actualizado_por  bigint        REFERENCES usuario(id),
    CONSTRAINT tarifa_envio_monto_valido CHECK (monto >= 0)
);

COMMENT ON TABLE tarifa_envio IS 'CCPIP-482: tarifa fija de envio a domicilio por distrito, en soles. Un distrito sin tarifa no se ofrece a domicilio.';

CREATE TABLE tarifa_envio_cambio (
    id              bigserial     PRIMARY KEY,
    ubigeo          char(6)       NOT NULL REFERENCES ubigeo(codigo),
    monto_anterior  numeric(10,2),
    monto_nuevo     numeric(10,2) NOT NULL,
    realizado_por   bigint        NOT NULL REFERENCES usuario(id),
    realizado_en    timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX tarifa_envio_cambio_fecha_idx ON tarifa_envio_cambio (realizado_en DESC);

COMMENT ON TABLE tarifa_envio_cambio IS 'CCPIP-482: quien fijo o cambio cada tarifa y cuando. Solo insercion; aparece en la auditoria (CCPIP-97).';

CREATE FUNCTION tarifa_envio_cambio_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'tarifa_envio_cambio es de solo insercion (CCPIP-482).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tarifa_envio_cambio_sin_update BEFORE UPDATE ON tarifa_envio_cambio
    FOR EACH ROW EXECUTE FUNCTION tarifa_envio_cambio_solo_insercion();
CREATE TRIGGER tarifa_envio_cambio_sin_delete BEFORE DELETE ON tarifa_envio_cambio
    FOR EACH ROW EXECUTE FUNCTION tarifa_envio_cambio_solo_insercion();

-- El pedido copia la tarifa al crearse (RNF-13): un cambio posterior no lo altera
ALTER TABLE pedido ADD COLUMN costo_envio numeric(10,2) NOT NULL DEFAULT 0;
ALTER TABLE pedido ADD CONSTRAINT pedido_costo_envio_valido CHECK (costo_envio >= 0);
ALTER TABLE pedido ADD CONSTRAINT pedido_recojo_sin_envio CHECK (modalidad = 'ENVIO' OR costo_envio = 0);

COMMENT ON COLUMN pedido.costo_envio IS 'CCPIP-483: tarifa de envio copiada al crear el pedido; 0 en recojo. El comprador paga total + costo_envio.';

CREATE FUNCTION pedido_envio_inmutable() RETURNS trigger AS $$
BEGIN
    IF NEW.costo_envio IS DISTINCT FROM OLD.costo_envio THEN
        RAISE EXCEPTION 'El costo de envio de un pedido no cambia (CCPIP-483).';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pedido_envio_congelado BEFORE UPDATE ON pedido
    FOR EACH ROW EXECUTE FUNCTION pedido_envio_inmutable();

-- El pago cubre los productos y el envio
CREATE OR REPLACE FUNCTION pago_al_crear()
RETURNS trigger AS $$
DECLARE
    pedido_estado varchar(30);
    pedido_monto  numeric(12,2);
BEGIN
    SELECT estado, total + costo_envio INTO pedido_estado, pedido_monto FROM pedido WHERE id = NEW.pedido_id;
    IF pedido_estado IS DISTINCT FROM 'PENDIENTE_PAGO' THEN
        RAISE EXCEPTION 'Solo se registra un pago para un pedido en PENDIENTE_PAGO (este esta en %).', pedido_estado;
    END IF;
    IF NEW.monto <> pedido_monto THEN
        RAISE EXCEPTION 'El monto del pago (%) debe ser igual al total del pedido con envio (%).', NEW.monto, pedido_monto;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
