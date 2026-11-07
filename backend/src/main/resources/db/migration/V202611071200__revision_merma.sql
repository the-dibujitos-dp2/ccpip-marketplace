-- CCPIP-120 (RF-62, C8). El administrador de un punto de acopio pide revisar una merma de su punto
-- que registro por error (por ejemplo, 10 unidades en vez de 1). Decision del cliente del 05/11/2026:
-- el subadministrador la aprueba o la rechaza, y la correccion se registra como un movimiento nuevo,
-- nunca editando la merma original (RNF-15, RNF-16: la merma sigue inmutable).
--
-- Al aprobar, las unidades que no debieron darse de baja vuelven a su lote con una ENTRADA de motivo
-- CORRECCION_MERMA: el saldo del lote y el stock suben, y cada saldo sigue explicado por sus movimientos.

ALTER TABLE movimiento_inventario DROP CONSTRAINT movimiento_motivo_valido;
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_motivo_valido CHECK (
    motivo IS NULL
 OR (tipo = 'ENTRADA' AND motivo IN ('RECEPCION', 'PRODUCCION', 'REINTEGRO_PEDIDO', 'CORRECCION_MERMA'))
 OR (tipo = 'SALIDA'  AND motivo IN ('DEVOLUCION_PRODUCTOR', 'RETIRO_PRODUCTOR'))
);

-- Una correccion dice a que lote vuelve y quien la aprobo
ALTER TABLE movimiento_inventario ADD CONSTRAINT movimiento_correccion_completa CHECK (
    motivo IS DISTINCT FROM 'CORRECCION_MERMA' OR (lote_id IS NOT NULL AND usuario_id IS NOT NULL)
);

COMMENT ON COLUMN movimiento_inventario.motivo IS 'CCPIP-121, 287, 283: RECEPCION o PRODUCCION en una ENTRADA manual; DEVOLUCION_PRODUCTOR o RETIRO_PRODUCTOR en una SALIDA. CCPIP-485/486: REINTEGRO_PEDIDO, unidades de un pedido que vuelven a su lote para darse de baja como merma. CCPIP-120: CORRECCION_MERMA, unidades de una merma registrada por error que vuelven a su lote al aprobarse la revision. Nulo en los demas movimientos automaticos.';

CREATE TABLE solicitud_revision_merma (
    id                       bigserial      PRIMARY KEY,
    merma_movimiento_id      bigint         NOT NULL REFERENCES merma(movimiento_id),
    solicitante_id           bigint         NOT NULL REFERENCES usuario(id),
    -- Las unidades que, segun el punto, no debieron darse de baja
    cantidad                 numeric(12, 3) NOT NULL,
    motivo                   varchar(500)   NOT NULL,
    estado                   varchar(10)    NOT NULL DEFAULT 'PENDIENTE',
    registrada_en            timestamptz    NOT NULL DEFAULT now(),
    resuelta_por             bigint         REFERENCES usuario(id),
    resuelta_en              timestamptz,
    respuesta                varchar(500),
    cantidad_aprobada        numeric(12, 3),
    correccion_movimiento_id bigint         REFERENCES movimiento_inventario(id),
    CONSTRAINT revision_merma_estado CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA')),
    CONSTRAINT revision_merma_cantidad CHECK (cantidad > 0),
    CONSTRAINT revision_merma_motivo CHECK (char_length(btrim(motivo)) >= 10),
    CONSTRAINT revision_merma_resolucion CHECK (
        (estado = 'PENDIENTE' AND resuelta_por IS NULL AND resuelta_en IS NULL AND cantidad_aprobada IS NULL
            AND correccion_movimiento_id IS NULL)
     OR (estado = 'APROBADA' AND resuelta_por IS NOT NULL AND resuelta_en IS NOT NULL
            AND cantidad_aprobada > 0 AND cantidad_aprobada <= cantidad AND correccion_movimiento_id IS NOT NULL)
     OR (estado = 'RECHAZADA' AND resuelta_por IS NOT NULL AND resuelta_en IS NOT NULL AND btrim(respuesta) <> ''
            AND cantidad_aprobada IS NULL AND correccion_movimiento_id IS NULL)
    )
);

-- Una merma tiene a lo mas una revision abierta o aprobada: tras un rechazo se puede volver a pedir
CREATE UNIQUE INDEX revision_merma_una_vigente ON solicitud_revision_merma (merma_movimiento_id)
    WHERE estado IN ('PENDIENTE', 'APROBADA');
CREATE INDEX revision_merma_estado_idx ON solicitud_revision_merma (estado, registrada_en DESC);

COMMENT ON TABLE solicitud_revision_merma IS
    'CCPIP-120: el punto pide revisar una merma registrada por error; el subadministrador la aprueba (la correccion es una ENTRADA CORRECCION_MERMA) o la rechaza. La merma original no cambia.';
