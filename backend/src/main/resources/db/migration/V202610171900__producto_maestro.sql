-- Catalogo maestro (CCPIP-42, RF-18): el producto generico que define la Camara, como "Cacao en
-- grano fermentado", y del que cada productor publica su propia oferta. La oferta sigue siendo la
-- fila de producto: conserva su productor, historia, precio y disponibilidad propios.
--
-- Por ahora el maestro entra sembrado. Crearlo, modificarlo y desactivarlo desde la plataforma son
-- CCPIP-312, 313 y 314. Mientras tanto una oferta creada por el productor nace sin maestro.

CREATE TABLE producto_maestro (
    id              bigserial    PRIMARY KEY,
    codigo          varchar(40)  NOT NULL UNIQUE,
    nombre          varchar(150) NOT NULL,
    descripcion     text,
    linea_id        smallint     NOT NULL REFERENCES linea_producto(id),
    activo          boolean      NOT NULL DEFAULT true,
    creado_en       timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE producto_maestro IS 'CCPIP-42: producto generico del catalogo de la Camara. Cada fila de producto que lo referencia es la oferta de un productor.';
COMMENT ON COLUMN producto_maestro.activo IS 'CCPIP-314: se desactiva, nunca se borra, para conservar las referencias historicas.';

ALTER TABLE producto ADD COLUMN producto_maestro_id bigint REFERENCES producto_maestro(id);
CREATE INDEX producto_maestro_idx ON producto (producto_maestro_id);

COMMENT ON COLUMN producto.producto_maestro_id IS 'CCPIP-42: producto del catalogo maestro del que esta fila es la oferta. Nula mientras la Camara no la asocie.';
