-- V3: ubicaciones
-- Cubre CCPIP-24 (carga de puntos de acopio) y el origen del recojo en CCPIP-157.
--
-- Una sola tabla para el almacen del productor y para el punto de acopio, porque
-- ambos siguen las mismas reglas de movimiento y merma (CCPIP-136). Distinguirlos
-- con dos tablas obligaria a duplicar stock y movimiento.
--
-- RNF-31: los mapas son simulados. No se guardan coordenadas para calcular rutas.
-- La cobertura de entrega por departamento es CCPIP-149 y entra en Sprint 1.

CREATE TABLE ubicacion (
    id              bigserial    PRIMARY KEY,
    tipo            varchar(20)  NOT NULL,
    nombre          varchar(160) NOT NULL,
    productor_id    bigint       REFERENCES productor(id),
    departamento    varchar(80)  NOT NULL,
    provincia       varchar(80),
    distrito        varchar(80),
    direccion       varchar(255),
    activo          boolean      NOT NULL DEFAULT true,
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ubicacion_tipo_valido CHECK (tipo IN ('PUNTO_ACOPIO', 'ALMACEN_PRODUCTOR')),
    -- Un almacen pertenece a un productor. Un punto de acopio es de la camara.
    CONSTRAINT ubicacion_dueno_coherente CHECK (
        (tipo = 'ALMACEN_PRODUCTOR' AND productor_id IS NOT NULL)
     OR (tipo = 'PUNTO_ACOPIO'      AND productor_id IS NULL)
    )
);

CREATE INDEX ubicacion_tipo_idx ON ubicacion (tipo);

-- Un productor tiene un solo almacen.
CREATE UNIQUE INDEX ubicacion_almacen_unico_idx
    ON ubicacion (productor_id)
    WHERE tipo = 'ALMACEN_PRODUCTOR';

-- Dos puntos de acopio no pueden llamarse igual. Ademas de ser cierto en el negocio,
-- es lo que permite que la carga sembrada de CCPIP-24 sea idempotente (RNF-28).
CREATE UNIQUE INDEX ubicacion_punto_nombre_unico_idx
    ON ubicacion (nombre)
    WHERE tipo = 'PUNTO_ACOPIO';

COMMENT ON TABLE ubicacion IS 'Puntos de acopio de la camara y almacenes de productor, en una sola tabla porque comparten reglas de inventario.';
COMMENT ON COLUMN ubicacion.tipo IS 'PUNTO_ACOPIO pertenece a la camara. ALMACEN_PRODUCTOR pertenece a un productor y es desde donde despacha al punto.';

-- El administrador de punto de acopio solo opera su propio punto (RNF-05).
-- La validacion se hace en la consulta al servidor, y esta tabla es lo que la sostiene.
CREATE TABLE usuario_ubicacion (
    usuario_id      bigint       NOT NULL REFERENCES usuario(id),
    ubicacion_id    bigint       NOT NULL REFERENCES ubicacion(id),
    PRIMARY KEY (usuario_id, ubicacion_id)
);

COMMENT ON TABLE usuario_ubicacion IS 'RNF-05: que punto de acopio administra cada usuario. El aislamiento se aplica filtrando por aqui en el servidor, no ocultando datos en la pantalla.';
