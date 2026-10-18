-- Alertas del productor (Figma PW-21) y del punto de acopio (Figma A-08):
--   CCPIP-145 (RF-111) consultar lotes proximos a vencer, con anticipacion configurable.
--   CCPIP-79  (RF-31)  alerta de lotes proximos a vencer.
--   CCPIP-337 (RF-227) alerta de lotes estancados: con saldo en un punto y sin salidas en N dias.
--   CCPIP-293 (RF-151) alerta de reposicion: una oferta publicada se quedo sin stock en venta.
--   CCPIP-366 (RF-256) aviso de nuevos intereses de compra en sus ofertas.
--   CCPIP-367 (RF-257) avisos de eventos operativos: transferencia recibida y pedido nuevo.
-- Cada alerta lleva producto, motivo (su tipo) y fecha. Las importantes salen tambien por correo.

-- Cada productor y cada punto fija su anticipacion y sus dias de estancado ("sin dias arbitrarios").
-- Sin fila rigen los provisionales de ccpip.alertas.* (application.properties).
CREATE TABLE preferencia_alerta (
    id                bigserial    PRIMARY KEY,
    productor_id      bigint       UNIQUE REFERENCES productor(id),
    ubicacion_id      bigint       UNIQUE REFERENCES ubicacion(id),
    dias_vencimiento  integer      NOT NULL,
    dias_estancado    integer      NOT NULL,
    -- Los tipos que no quiere recibir; por defecto recibe todos los de su rol
    tipos_silenciados varchar(30)[] NOT NULL DEFAULT '{}',
    correo            boolean      NOT NULL DEFAULT true,
    actualizado_por   bigint       NOT NULL REFERENCES usuario(id),
    actualizado_en    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT preferencia_alerta_un_destino CHECK (num_nonnulls(productor_id, ubicacion_id) = 1),
    CONSTRAINT preferencia_alerta_dias_vencimiento CHECK (dias_vencimiento BETWEEN 1 AND 365),
    CONSTRAINT preferencia_alerta_dias_estancado CHECK (dias_estancado BETWEEN 1 AND 365),
    CONSTRAINT preferencia_alerta_tipos_validos CHECK (tipos_silenciados <@ ARRAY[
        'POR_VENCER', 'ESTANCADO', 'REPOSICION', 'INTERES', 'TRANSFERENCIA_RECIBIDA', 'PEDIDO_NUEVO']::varchar[])
);

COMMENT ON TABLE preferencia_alerta IS
    'PW-21 y A-08 "Configurar alertas": anticipacion, dias de estancado, tipos y correo de un productor o de un punto de acopio.';

CREATE TABLE alerta (
    id            bigserial    PRIMARY KEY,
    tipo          varchar(30)  NOT NULL,
    -- A quien va: un productor o un punto de acopio (la ven todos sus administradores)
    productor_id  bigint       REFERENCES productor(id),
    ubicacion_id  bigint       REFERENCES ubicacion(id),
    -- Lote (POR_VENCER, ESTANCADO), producto (REPOSICION, INTERES), transferencia o pedido
    referencia_id bigint       NOT NULL,
    producto_id   bigint       REFERENCES producto(id),
    -- Vence el, sin salidas desde, agotada desde, ultimo interes, recibida el, confirmado el
    fecha         date         NOT NULL,
    creada_en     timestamptz  NOT NULL DEFAULT now(),
    leida_en      timestamptz,
    -- Una condicion que dejo de cumplirse (el lote se vendio, la oferta volvio a tener stock).
    -- Los eventos (transferencia, pedido) no se resuelven.
    resuelta_en   timestamptz,
    correo        varchar(12)  NOT NULL DEFAULT 'NO_APLICA',
    correo_error  varchar(300),
    CONSTRAINT alerta_tipo_valido CHECK (tipo IN (
        'POR_VENCER', 'ESTANCADO', 'REPOSICION', 'INTERES', 'TRANSFERENCIA_RECIBIDA', 'PEDIDO_NUEVO')),
    CONSTRAINT alerta_un_destino CHECK (num_nonnulls(productor_id, ubicacion_id) = 1),
    -- El punto solo recibe alertas de sus lotes
    CONSTRAINT alerta_tipo_del_punto CHECK (ubicacion_id IS NULL OR tipo IN ('POR_VENCER', 'ESTANCADO')),
    CONSTRAINT alerta_correo_valido CHECK (correo IN ('NO_APLICA', 'PENDIENTE', 'ENVIADO', 'FALLIDO'))
);

-- Una sola alerta abierta por hecho y destino: la revision periodica puede correr las veces que sea
CREATE UNIQUE INDEX alerta_productor_abierta ON alerta (tipo, productor_id, referencia_id)
    WHERE productor_id IS NOT NULL AND resuelta_en IS NULL;
CREATE UNIQUE INDEX alerta_punto_abierta ON alerta (tipo, ubicacion_id, referencia_id)
    WHERE ubicacion_id IS NOT NULL AND resuelta_en IS NULL;
CREATE INDEX alerta_productor_idx ON alerta (productor_id, creada_en DESC) WHERE productor_id IS NOT NULL;
CREATE INDEX alerta_ubicacion_idx ON alerta (ubicacion_id, creada_en DESC) WHERE ubicacion_id IS NOT NULL;
CREATE INDEX alerta_correo_pendiente_idx ON alerta (id) WHERE correo = 'PENDIENTE';

COMMENT ON TABLE alerta IS
    'CCPIP-79, 337, 293, 366 y 367: bandeja de alertas del productor (PW-21) y del punto (A-08). La genera una revision periodica.';

-- Para encontrar rapido la ultima salida de un lote (estancados)
CREATE INDEX movimiento_lote_salida_idx ON movimiento_inventario (lote_id, registrado_en DESC)
    WHERE tipo IN ('SALIDA', 'SALIDA_VENTA', 'TRANSFERENCIA_SALIDA');
