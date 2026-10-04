-- Imagenes de la tienda y de los productos (CCPIP-30 portada y galeria, CCPIP-44 fotos).
-- El archivo vive en S3 (MinIO en desarrollo y CI); aqui solo la referencia y sus datos.

CREATE TABLE imagen (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    productor_id  bigint       NOT NULL REFERENCES productor(id),
    producto_id   bigint       REFERENCES producto(id),
    tipo          varchar(10)  NOT NULL,
    categoria     varchar(20),
    clave         varchar(200) NOT NULL UNIQUE,
    clave_miniatura varchar(200) NOT NULL UNIQUE,
    ancho         integer      NOT NULL,
    alto          integer      NOT NULL,
    bytes         integer      NOT NULL,
    orden         smallint     NOT NULL DEFAULT 0,
    principal     boolean      NOT NULL DEFAULT false,
    subida_en     timestamptz  NOT NULL DEFAULT now(),
    subida_por    bigint       REFERENCES usuario(id),
    CONSTRAINT imagen_tipo_valido CHECK (tipo IN ('PORTADA', 'GALERIA', 'PRODUCTO')),
    CONSTRAINT imagen_categoria_valida CHECK (
        categoria IS NULL OR categoria IN ('COMUNIDAD', 'CULTIVO', 'PRODUCCION', 'TIENDA_FISICA', 'BRANDING')),
    -- Solo las fotos de producto cuelgan de un producto; portada y galeria son de la tienda
    CONSTRAINT imagen_producto_coherente CHECK ((tipo = 'PRODUCTO') = (producto_id IS NOT NULL)),
    CONSTRAINT imagen_principal_solo_producto CHECK (NOT principal OR tipo = 'PRODUCTO')
);

CREATE INDEX imagen_productor_idx ON imagen (productor_id, tipo);
CREATE INDEX imagen_producto_idx ON imagen (producto_id, orden);
-- Una sola portada por tienda y una sola foto principal por producto
CREATE UNIQUE INDEX imagen_portada_unica_idx ON imagen (productor_id) WHERE tipo = 'PORTADA';
CREATE UNIQUE INDEX imagen_principal_unica_idx ON imagen (producto_id) WHERE principal;

COMMENT ON COLUMN imagen.clave IS 'Llave del objeto en el bucket. La imagen se sirve por /api/imagenes/{id}, nunca con la URL del bucket.';
