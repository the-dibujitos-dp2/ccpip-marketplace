-- CCPIP-269 (RF-142): solo se compra lo autorizado a un productor con cuenta bancaria activa.
-- Solo entra lo que la regla de compra necesita. El registro y la edicion de la cuenta
-- (CCPIP-32, CCPIP-274, CCPIP-275) y el flujo de autorizacion con evidencias (CCPIP-359 y la
-- bandeja S-01/S-02 del subadministrador) agregan sus columnas y tablas con sus historias.

CREATE TABLE cuenta_bancaria (
    id              bigserial    PRIMARY KEY,
    productor_id    bigint       NOT NULL REFERENCES productor(id),
    banco           varchar(80)  NOT NULL,
    titular         varchar(160) NOT NULL,
    numero_cuenta   varchar(30)  NOT NULL,
    cci             char(20),
    estado          varchar(12)  NOT NULL DEFAULT 'ACTIVA',
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT cuenta_bancaria_estado_valido CHECK (estado IN ('ACTIVA', 'DESACTIVADA'))
);

-- Como maximo una cuenta ACTIVA por productor; las desactivadas se conservan por el historial.
CREATE UNIQUE INDEX cuenta_bancaria_una_activa ON cuenta_bancaria (productor_id) WHERE estado = 'ACTIVA';

COMMENT ON TABLE cuenta_bancaria IS 'Cuenta donde la Camara liquida al productor. Vender exige una cuenta ACTIVA (RF-142). No vence (CCPIP-129).';

CREATE TABLE autorizacion_producto (
    id              bigserial    PRIMARY KEY,
    productor_id    bigint       NOT NULL REFERENCES productor(id),
    producto_id     bigint       NOT NULL REFERENCES producto(id),
    estado          varchar(12)  NOT NULL DEFAULT 'SOLICITADA',
    motivo          varchar(255),
    decidido_por    bigint       REFERENCES usuario(id),
    decidido_en     timestamptz,
    vigente_hasta   date,
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT autorizacion_estado_valido CHECK (estado IN ('SOLICITADA', 'APROBADA', 'RECHAZADA', 'REVOCADA')),
    CONSTRAINT autorizacion_producto_unica UNIQUE (productor_id, producto_id)
);

COMMENT ON TABLE autorizacion_producto IS 'Autorizacion de un productor para vender un producto. Se compra solo con estado APROBADA y vigente_hasta nula o no vencida (RF-142).';
COMMENT ON COLUMN autorizacion_producto.producto_id IS 'El diccionario la liga a producto_maestro, que aun no existe. Cuando entre el catalogo maestro se migra a producto_maestro_id.';
COMMENT ON COLUMN autorizacion_producto.vigente_hasta IS 'Ultimo dia en que la autorizacion vale, en hora de Lima. Nula si la evidencia no fija vigencia.';

-- Lo que ya se vendia antes de esta regla queda autorizado, para no sacarlo de venta de golpe.
-- No se inventan cuentas bancarias: un productor sin cuenta ACTIVA deja de poder vender.
INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
SELECT productor_id, id, 'APROBADA', now() FROM producto;
