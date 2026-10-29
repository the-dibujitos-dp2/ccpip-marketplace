-- CCPIP-177, CCPIP-368 y CCPIP-369 (RF-152, RF-258, RF-259): el consumidor guarda productos, productores y
-- comunidades como favoritos. Consultarlos (CCPIP-327, CCPIP-385, CCPIP-386) y retirarlos (CCPIP-396,
-- CCPIP-397, CCPIP-398) se apoyan en estas tablas. Un favorito no registra cantidad, intencion de compra,
-- pago ni reserva: solo quien lo guardo, que guardo y cuando.

CREATE TABLE favorito_producto (
    usuario_id   bigint       NOT NULL REFERENCES usuario(id),
    producto_id  bigint       NOT NULL REFERENCES producto(id),
    guardado_en  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, producto_id)
);

CREATE TABLE favorito_productor (
    usuario_id    bigint       NOT NULL REFERENCES usuario(id),
    productor_id  bigint       NOT NULL REFERENCES productor(id),
    guardado_en   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, productor_id)
);

-- La comunidad no tiene tabla propia: es el texto productor.comunidad, y su pagina es la tienda. Se guarda el
-- nombre y la tienda desde la que se guardo; la lista muestra las tiendas publicadas con esa comunidad.
CREATE TABLE favorito_comunidad (
    id            bigserial     PRIMARY KEY,
    usuario_id    bigint        NOT NULL REFERENCES usuario(id),
    productor_id  bigint        NOT NULL REFERENCES productor(id),
    comunidad     varchar(160)  NOT NULL,
    guardado_en   timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX favorito_comunidad_unica_idx
    ON favorito_comunidad (usuario_id, lower(inmutable_unaccent(btrim(comunidad))));

COMMENT ON TABLE favorito_producto IS 'CCPIP-177: productos favoritos del consumidor. Retirarlo (CCPIP-396) borra la fila; el producto no cambia.';
COMMENT ON TABLE favorito_productor IS 'CCPIP-368: productores favoritos del consumidor. Retirarlo (CCPIP-397) no toca la cuenta ni la tienda.';
COMMENT ON TABLE favorito_comunidad IS 'CCPIP-369: comunidades favoritas del consumidor, por nombre. Retirarla (CCPIP-398) no toca la comunidad.';
COMMENT ON COLUMN favorito_comunidad.productor_id IS 'La tienda desde la que se guardo; sirve para retirarla desde esa misma tienda.';
