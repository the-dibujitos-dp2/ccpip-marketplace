-- Pago y su estado. Modelado en CCPIP-19 y CCPIP-148 para CCPIP-62 (pagar con tarjeta en una
-- pasarela de pruebas), CCPIP-163 (estado de cada intento) y CCPIP-157 (confirmar con pago aprobado).
--
-- El pedido existe ANTES de pagarse, en PENDIENTE_PAGO, y solo pasa a CONFIRMADO cuando un intento
-- sale APROBADO. Recien ahi se descuenta el stock (RNF-10), en la misma transaccion. Un rechazo no
-- confirma ni descuenta (CCPIP-62).
--
-- Nunca se guarda la tarjeta: ni numero, ni vencimiento, ni CVV. Solo la marca, que no es dato
-- sensible. La tarjeta vive en memoria mientras se valida y se envia a la pasarela.

-- ---------------------------------------------------------------------------------------------
-- pedido: estado previo al pago
-- ---------------------------------------------------------------------------------------------

ALTER TABLE pedido DROP CONSTRAINT pedido_estado_valido;
ALTER TABLE pedido ADD CONSTRAINT pedido_estado_valido CHECK (estado IN (
    'PENDIENTE_PAGO', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'ENTREGADO', 'CANCELADO'));

-- Hasta ahora un pedido nacia confirmado, asi que no tenia fecha de creacion propia.
ALTER TABLE pedido ADD COLUMN creado_en timestamptz NOT NULL DEFAULT now();
UPDATE pedido SET creado_en = confirmado_en;

-- confirmado_en se llena al confirmar. El valor por defecto se mantiene para lo que ya inserta
-- pedidos confirmados; CCPIP-157 inserta PENDIENTE_PAGO con confirmado_en nulo.
ALTER TABLE pedido ALTER COLUMN confirmado_en DROP NOT NULL;
ALTER TABLE pedido ADD CONSTRAINT pedido_confirmacion_coherente CHECK (
    (estado = 'PENDIENTE_PAGO' AND confirmado_en IS NULL)
 OR estado = 'CANCELADO'
 OR (estado NOT IN ('PENDIENTE_PAGO', 'CANCELADO') AND confirmado_en IS NOT NULL)
);

COMMENT ON COLUMN pedido.estado IS 'PENDIENTE_PAGO hasta que un pago sale APROBADO; recien ahi CONFIRMADO y se descuenta stock. Un pedido sin pagar puede pasar a CANCELADO.';
COMMENT ON COLUMN pedido.confirmado_en IS 'Momento de la confirmacion, con el pago aprobado. Nulo mientras el pedido espera su pago o si se cancelo sin pagarse.';

-- ---------------------------------------------------------------------------------------------
-- pago: un intento por fila
-- ---------------------------------------------------------------------------------------------

CREATE TABLE pago (
    id                  bigserial     PRIMARY KEY,
    pedido_id           bigint        NOT NULL REFERENCES pedido(id),
    clave_idempotencia  uuid          NOT NULL,
    metodo              varchar(20)   NOT NULL DEFAULT 'TARJETA',
    monto               numeric(12,2) NOT NULL,
    estado              varchar(20)   NOT NULL DEFAULT 'PENDIENTE',
    pasarela            varchar(40)   NOT NULL,
    referencia_pasarela varchar(100),
    marca_tarjeta       varchar(20),
    codigo_respuesta    varchar(40),
    mensaje_respuesta   varchar(255),
    creado_en           timestamptz   NOT NULL DEFAULT now(),
    resuelto_en         timestamptz,
    anulado_en          timestamptz,
    -- Reintentar el mismo intento (doble clic, corte de red) no crea otro ni cobra dos veces
    CONSTRAINT pago_clave_idempotencia_unica UNIQUE (clave_idempotencia),
    -- YAPE u otro metodo local entra con CCPIP-183
    CONSTRAINT pago_metodo_valido CHECK (metodo IN ('TARJETA')),
    CONSTRAINT pago_monto_positivo CHECK (monto > 0),
    CONSTRAINT pago_estado_valido CHECK (estado IN ('PENDIENTE', 'APROBADO', 'RECHAZADO', 'ERROR', 'ANULADO')),
    CONSTRAINT pago_marca_valida CHECK (marca_tarjeta IN ('VISA', 'MASTERCARD', 'AMEX', 'DINERS')),
    -- La marca se valida antes de llamar a la pasarela, asi que un intento con tarjeta siempre la tiene
    CONSTRAINT pago_tarjeta_con_marca CHECK (metodo <> 'TARJETA' OR marca_tarjeta IS NOT NULL),
    CONSTRAINT pago_resuelto_coherente CHECK ((estado = 'PENDIENTE') = (resuelto_en IS NULL)),
    CONSTRAINT pago_anulado_coherente CHECK ((estado = 'ANULADO') = (anulado_en IS NOT NULL)),
    -- Un cobro aprobado siempre se puede rastrear (y devolver) en la pasarela
    CONSTRAINT pago_aprobado_con_referencia CHECK (estado NOT IN ('APROBADO', 'ANULADO') OR referencia_pasarela IS NOT NULL)
);

CREATE INDEX pago_pedido_idx ON pago (pedido_id, creado_en);

-- Sin doble cobro aunque el codigo falle: un solo pago aprobado por pedido...
CREATE UNIQUE INDEX pago_un_aprobado_por_pedido_idx ON pago (pedido_id) WHERE estado = 'APROBADO';
-- ...y un solo intento en curso a la vez.
CREATE UNIQUE INDEX pago_un_pendiente_por_pedido_idx ON pago (pedido_id) WHERE estado = 'PENDIENTE';
CREATE UNIQUE INDEX pago_referencia_unica_idx ON pago (pasarela, referencia_pasarela) WHERE referencia_pasarela IS NOT NULL;

COMMENT ON TABLE pago IS 'CCPIP-62 y CCPIP-163: cada intento de pago de un pedido. Un reintento es una fila nueva; un estado final no se reescribe. No guarda numero, vencimiento ni CVV de la tarjeta.';
COMMENT ON COLUMN pago.clave_idempotencia IS 'La genera el frontend por intento. Si el mismo intento llega dos veces, se devuelve el ya registrado en vez de cobrar otra vez.';
COMMENT ON COLUMN pago.estado IS 'PENDIENTE mientras responde la pasarela. APROBADO, RECHAZADO o ERROR son finales. ANULADO solo desde APROBADO, cuando el stock ya no alcanzo al confirmar y se devolvio el cobro.';
COMMENT ON COLUMN pago.monto IS 'En soles (RNF-43). Igual al total del pedido al crear el intento.';

-- ---------------------------------------------------------------------------------------------
-- Reglas que viven en la base, porque una regla solo en el codigo se salta desde cualquier cliente
-- ---------------------------------------------------------------------------------------------

-- Solo se paga un pedido que espera su pago, y por su total exacto.
CREATE OR REPLACE FUNCTION pago_al_crear()
RETURNS trigger AS $$
DECLARE
    pedido_estado varchar(30);
    pedido_total  numeric(12,2);
BEGIN
    SELECT estado, total INTO pedido_estado, pedido_total FROM pedido WHERE id = NEW.pedido_id;
    IF pedido_estado IS DISTINCT FROM 'PENDIENTE_PAGO' THEN
        RAISE EXCEPTION 'Solo se registra un pago para un pedido en PENDIENTE_PAGO (este esta en %).', pedido_estado;
    END IF;
    IF NEW.monto <> pedido_total THEN
        RAISE EXCEPTION 'El monto del pago (%) debe ser igual al total del pedido (%).', NEW.monto, pedido_total;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pago_validar_al_crear
    BEFORE INSERT ON pago
    FOR EACH ROW EXECUTE FUNCTION pago_al_crear();

-- Un intento no se borra ni cambia de pedido, monto o clave. Su estado solo avanza:
-- PENDIENTE -> APROBADO | RECHAZADO | ERROR, y APROBADO -> ANULADO. Lo demas es final.
CREATE OR REPLACE FUNCTION pago_al_cambiar()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Un pago no se borra (CCPIP-163): cada intento queda registrado.';
    END IF;
    IF NEW.pedido_id <> OLD.pedido_id OR NEW.monto <> OLD.monto OR NEW.metodo <> OLD.metodo
       OR NEW.clave_idempotencia <> OLD.clave_idempotencia OR NEW.creado_en <> OLD.creado_en THEN
        RAISE EXCEPTION 'Un pago no cambia de pedido, monto, metodo ni clave.';
    END IF;
    IF NOT ((OLD.estado = 'PENDIENTE' AND NEW.estado IN ('PENDIENTE', 'APROBADO', 'RECHAZADO', 'ERROR'))
         OR (OLD.estado = 'APROBADO' AND NEW.estado = 'ANULADO')) THEN
        RAISE EXCEPTION 'Transicion de pago no permitida: % -> %. Para reintentar se registra un pago nuevo.',
            OLD.estado, NEW.estado;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pago_sin_reescribir
    BEFORE UPDATE OR DELETE ON pago
    FOR EACH ROW EXECUTE FUNCTION pago_al_cambiar();
