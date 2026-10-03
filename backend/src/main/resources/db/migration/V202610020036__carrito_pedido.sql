-- V5: carrito y pedido
-- Cubre CCPIP-58, CCPIP-155, CCPIP-156 (carrito), CCPIP-157 (confirmar con recojo),
-- CCPIP-64 (confirmacion), CCPIP-159 y CCPIP-71 (estados y recojo), CCPIP-112 (historico).
--
-- El carrito se persiste en base de datos y no en el navegador. Era el punto abierto
-- numero 1 del informe de arquitectura: el modulo lo mencionaba y el modelo no lo tenia.

CREATE TABLE carrito (
    id              bigserial    PRIMARY KEY,
    usuario_id      bigint       NOT NULL UNIQUE REFERENCES usuario(id),
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    actualizado_en  timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE carrito IS 'Un carrito vigente por usuario. actualizado_en es la base del tiempo de vida de CCPIP-142, que entra en Sprint 2.';

CREATE TABLE item_carrito (
    id              bigserial    PRIMARY KEY,
    carrito_id      bigint       NOT NULL REFERENCES carrito(id) ON DELETE CASCADE,
    producto_id     bigint       NOT NULL REFERENCES producto(id),
    cantidad        numeric(12,3) NOT NULL,
    agregado_en     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT item_carrito_cantidad_positiva CHECK (cantidad > 0),
    -- Un producto aparece una sola vez por carrito: agregarlo de nuevo suma cantidad.
    CONSTRAINT item_carrito_producto_unico UNIQUE (carrito_id, producto_id)
);

COMMENT ON TABLE item_carrito IS 'RNF-35: estar en el carrito no reserva ni descuenta stock. El descuento ocurre al confirmar el pedido.';

CREATE TABLE pedido (
    id              bigserial    PRIMARY KEY,
    numero          varchar(20)  NOT NULL UNIQUE,
    usuario_id      bigint       NOT NULL REFERENCES usuario(id),
    modalidad       varchar(20)  NOT NULL,
    ubicacion_origen_id bigint   NOT NULL REFERENCES ubicacion(id),
    estado          varchar(30)  NOT NULL DEFAULT 'CONFIRMADO',
    total           numeric(12,2) NOT NULL,
    confirmado_en   timestamptz  NOT NULL DEFAULT now(),
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pedido_modalidad_valida CHECK (modalidad IN ('RECOJO', 'ENVIO')),
    CONSTRAINT pedido_total_no_negativo CHECK (total >= 0),
    CONSTRAINT pedido_estado_valido CHECK (estado IN ('CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'ENTREGADO', 'CANCELADO'))
);

CREATE INDEX pedido_usuario_idx ON pedido (usuario_id, confirmado_en DESC);
CREATE INDEX pedido_ubicacion_estado_idx ON pedido (ubicacion_origen_id, estado);

COMMENT ON COLUMN pedido.estado IS 'En Sprint 0 solo se usan CONFIRMADO, LISTO_PARA_RECOJO y ENTREGADO. EN_PREPARACION entra con CCPIP-68 en Sprint 1.';
COMMENT ON COLUMN pedido.modalidad IS 'En Sprint 0 solo RECOJO. ENVIO entra con CCPIP-59 en Sprint 1, cuando exista la cobertura de CCPIP-149.';
COMMENT ON COLUMN pedido.total IS 'En soles. RNF-43: la plataforma no maneja mas de una moneda, por eso no hay columna de divisa.';

CREATE TABLE item_pedido (
    id              bigserial    PRIMARY KEY,
    pedido_id       bigint       NOT NULL REFERENCES pedido(id),
    producto_id     bigint       NOT NULL REFERENCES producto(id),
    productor_id    bigint       NOT NULL REFERENCES productor(id),
    cantidad        numeric(12,3) NOT NULL,
    precio_unitario numeric(10,2) NOT NULL,
    subtotal        numeric(12,2) NOT NULL,
    CONSTRAINT item_pedido_cantidad_positiva CHECK (cantidad > 0),
    CONSTRAINT item_pedido_precio_positivo CHECK (precio_unitario > 0)
);

CREATE INDEX item_pedido_pedido_idx ON item_pedido (pedido_id);
CREATE INDEX item_pedido_productor_idx ON item_pedido (productor_id);

COMMENT ON COLUMN item_pedido.precio_unitario IS 'RNF-13: copia del precio al momento de confirmar. Un cambio posterior en producto.precio no altera este pedido. Nunca se lee el precio vigente para mostrar un pedido pasado.';
COMMENT ON COLUMN item_pedido.productor_id IS 'Se copia para que el aislamiento de RNF-05 no dependa de seguir la cadena hasta producto, que puede haber cambiado de estado.';

-- Historial de estados. Lo pide CCPIP-159 (quien marco listo) y CCPIP-71 (quien
-- registro el recojo), y lo necesita CCPIP-70 para mostrar el historial al comprador.
CREATE TABLE pedido_estado_historial (
    id              bigserial    PRIMARY KEY,
    pedido_id       bigint       NOT NULL REFERENCES pedido(id),
    estado          varchar(30)  NOT NULL,
    usuario_id      bigint       REFERENCES usuario(id),
    registrado_en   timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX pedido_historial_idx ON pedido_estado_historial (pedido_id, registrado_en);

-- Ahora que pedido existe, se cierra la llave foranea que la migracion anterior dejo pendiente.
ALTER TABLE movimiento_inventario
    ADD CONSTRAINT movimiento_pedido_fk FOREIGN KEY (pedido_id) REFERENCES pedido(id);
