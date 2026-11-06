-- CCPIP-484: capacidad diaria de entregas a domicilio por punto de acopio. Decision del cliente del 05/11/2026
-- (origen CCPIP-149 y RNF-45): la "capacidad aprobada" es un maximo de pedidos a domicilio por dia (Lima) y por
-- punto, que fija la Camara. Sin capacidad registrada el punto no tiene limite.
--
-- Cuentan los pedidos a domicilio que salen del punto y se crearon ese dia, salvo los cancelados: un pedido
-- que espera su pago ya ocupa un cupo (si no se paga, se cancela y lo libera).

CREATE TABLE capacidad_entrega (
    ubicacion_id     bigint      PRIMARY KEY REFERENCES ubicacion(id),
    maximo_diario    integer     NOT NULL,
    actualizado_en   timestamptz NOT NULL DEFAULT now(),
    actualizado_por  bigint      REFERENCES usuario(id),
    CONSTRAINT capacidad_entrega_maximo_valido CHECK (maximo_diario BETWEEN 1 AND 10000)
);

COMMENT ON TABLE capacidad_entrega IS 'CCPIP-484: maximo de pedidos a domicilio por dia (Lima) de cada punto de acopio. Sin fila, sin limite.';

CREATE TABLE capacidad_entrega_cambio (
    id               bigserial   PRIMARY KEY,
    ubicacion_id     bigint      NOT NULL REFERENCES ubicacion(id),
    maximo_anterior  integer,
    maximo_nuevo     integer,
    realizado_por    bigint      NOT NULL REFERENCES usuario(id),
    realizado_en     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT capacidad_entrega_cambio_hay_cambio CHECK (maximo_anterior IS DISTINCT FROM maximo_nuevo)
);

CREATE INDEX capacidad_entrega_cambio_fecha_idx ON capacidad_entrega_cambio (realizado_en DESC);

COMMENT ON TABLE capacidad_entrega_cambio IS 'CCPIP-484: quien fijo, cambio o quito cada capacidad (maximo_nuevo nulo = sin limite). Solo insercion; aparece en la auditoria (CCPIP-97).';

CREATE FUNCTION capacidad_entrega_cambio_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'capacidad_entrega_cambio es de solo insercion (CCPIP-484).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER capacidad_entrega_cambio_sin_update BEFORE UPDATE ON capacidad_entrega_cambio
    FOR EACH ROW EXECUTE FUNCTION capacidad_entrega_cambio_solo_insercion();
CREATE TRIGGER capacidad_entrega_cambio_sin_delete BEFORE DELETE ON capacidad_entrega_cambio
    FOR EACH ROW EXECUTE FUNCTION capacidad_entrega_cambio_solo_insercion();

-- Para contar los pedidos a domicilio del dia de cada punto
CREATE INDEX pedido_envio_del_dia_idx ON pedido (ubicacion_origen_id, creado_en)
    WHERE modalidad = 'ENVIO' AND estado <> 'CANCELADO';
