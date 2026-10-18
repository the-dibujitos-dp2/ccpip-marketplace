-- CCPIP-105 (RF-38): el consumidor registra su interes de compra, con cantidad, en una oferta
-- concreta (producto y productor) que esta agotada o en "Venta pausada". No es pago, reserva ni
-- movimiento de inventario: solo una intencion.
-- CCPIP-106 (RF-24): el productor ve cuantos interesados y que cantidad tiene cada oferta suya.
-- CCPIP-273 (RF-150): cuando la oferta vuelve a ser vendible se avisa una vez y el interes se cierra.
-- CCPIP-69 (RF-51): el comprador recibe un aviso cuando su pedido esta listo para recojo.
-- El canal es el correo mientras CCPIP-103 no defina otro; cada aviso queda en "aviso", sin duplicar.

CREATE TABLE interes_compra (
    id             bigserial    PRIMARY KEY,
    usuario_id     bigint       NOT NULL REFERENCES usuario(id),
    producto_id    bigint       NOT NULL REFERENCES producto(id),
    -- Copia del productor de la oferta: el interes es por esa oferta, no por "el producto" en general
    productor_id   bigint       NOT NULL REFERENCES productor(id),
    cantidad       integer      NOT NULL,
    -- Por que no se podia comprar al registrarlo
    motivo         varchar(20)  NOT NULL,
    estado         varchar(20)  NOT NULL DEFAULT 'PENDIENTE',
    registrado_en  timestamptz  NOT NULL DEFAULT now(),
    actualizado_en timestamptz  NOT NULL DEFAULT now(),
    avisado_en     timestamptz,
    CONSTRAINT interes_cantidad_valida CHECK (cantidad > 0 AND cantidad <= 9999),
    CONSTRAINT interes_motivo_valido CHECK (motivo IN ('AGOTADO', 'VENTA_PAUSADA')),
    CONSTRAINT interes_estado_valido CHECK (estado IN ('PENDIENTE', 'AVISADO', 'RETIRADO')),
    CONSTRAINT interes_avisado_con_fecha CHECK ((estado = 'AVISADO') = (avisado_en IS NOT NULL))
);

-- Un solo interes pendiente por comprador y oferta: registrarlo otra vez cambia la cantidad
CREATE UNIQUE INDEX interes_compra_pendiente_unico ON interes_compra (usuario_id, producto_id)
    WHERE estado = 'PENDIENTE';
CREATE INDEX interes_compra_productor_idx ON interes_compra (productor_id) WHERE estado = 'PENDIENTE';
CREATE INDEX interes_compra_producto_idx ON interes_compra (producto_id) WHERE estado = 'PENDIENTE';

COMMENT ON TABLE interes_compra IS
    'CCPIP-105: intencion de compra sobre una oferta agotada o pausada. Sin reserva ni cobro. Se cierra al avisar (CCPIP-273).';

-- La oferta y su productor no se separan: el productor del interes es el del producto
CREATE OR REPLACE FUNCTION interes_compra_productor_de_la_oferta()
RETURNS trigger AS $$
BEGIN
    IF NEW.productor_id IS DISTINCT FROM (SELECT productor_id FROM producto WHERE id = NEW.producto_id)
       OR (TG_OP = 'UPDATE' AND (NEW.producto_id, NEW.productor_id, NEW.usuario_id)
                                 IS DISTINCT FROM (OLD.producto_id, OLD.productor_id, OLD.usuario_id)) THEN
        RAISE EXCEPTION 'El interes es por una oferta y un productor concretos (CCPIP-105).';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER interes_compra_oferta BEFORE INSERT OR UPDATE ON interes_compra
    FOR EACH ROW EXECUTE FUNCTION interes_compra_productor_de_la_oferta();

-- Registro de avisos enviados (o intentados) a una persona. La clave unica impide avisar dos veces
-- lo mismo por el mismo canal.
CREATE TABLE aviso (
    id            bigserial    PRIMARY KEY,
    usuario_id    bigint       NOT NULL REFERENCES usuario(id),
    tipo          varchar(30)  NOT NULL,
    -- Pedido (PEDIDO_LISTO) o interes (INTERES_VENDIBLE) que lo origina
    referencia_id bigint       NOT NULL,
    canal         varchar(20)  NOT NULL DEFAULT 'CORREO',
    estado        varchar(20)  NOT NULL DEFAULT 'PENDIENTE',
    registrado_en timestamptz  NOT NULL DEFAULT now(),
    enviado_en    timestamptz,
    error         varchar(300),
    CONSTRAINT aviso_tipo_valido CHECK (tipo IN ('PEDIDO_LISTO', 'INTERES_VENDIBLE')),
    CONSTRAINT aviso_canal_valido CHECK (canal IN ('CORREO')),
    CONSTRAINT aviso_estado_valido CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'FALLIDO')),
    CONSTRAINT aviso_enviado_con_fecha CHECK ((estado = 'ENVIADO') = (enviado_en IS NOT NULL)),
    CONSTRAINT aviso_sin_duplicar UNIQUE (tipo, referencia_id, usuario_id, canal)
);

CREATE INDEX aviso_usuario_idx ON aviso (usuario_id, registrado_en DESC);

COMMENT ON TABLE aviso IS 'CCPIP-69 y CCPIP-273: avisos a compradores, uno por hecho y canal (sin duplicacion).';
