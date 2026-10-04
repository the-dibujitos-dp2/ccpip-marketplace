-- Publicacion de productos por el propio productor (CCPIP-40, 45, 47, 132, 143, 160).
-- Hasta ahora los productos entraban sembrados y completos. Ahora nacen como borrador desde
-- la plataforma, asi que los datos que se piden al publicar pueden faltar en un borrador.

-- CCPIP-45: cada linea sugiere su unidad y decide si el lote necesita fecha de vencimiento
-- (una artesania no vence). Los valores los fija R__01.
ALTER TABLE linea_producto
    ADD COLUMN requiere_vencimiento boolean  NOT NULL DEFAULT true,
    ADD COLUMN unidad_sugerida_id   smallint REFERENCES unidad_medida(id),
    ADD COLUMN orden                smallint NOT NULL DEFAULT 0;

-- Un borrador puede no tener aun linea, unidad ni precio. Publicar los exige (validado en
-- el servidor, CCPIP-40), y el catalogo solo lee productos publicados.
ALTER TABLE producto
    ALTER COLUMN linea_id DROP NOT NULL,
    ALTER COLUMN unidad_medida_id DROP NOT NULL,
    ALTER COLUMN precio DROP NOT NULL,
    ADD COLUMN contenido                 varchar(40),
    ADD COLUMN modalidad                 varchar(12)   NOT NULL DEFAULT 'MINORISTA',
    ADD COLUMN precio_mayorista          numeric(10,2),
    ADD COLUMN cantidad_minima_mayorista integer,
    ADD COLUMN destacado_tienda          boolean       NOT NULL DEFAULT false,
    ADD COLUMN descripcion_propuesta     text,
    ADD COLUMN descripcion_asistida      boolean       NOT NULL DEFAULT false,
    ADD COLUMN publicado_en              timestamptz,
    ADD COLUMN creado_por                bigint        REFERENCES usuario(id),
    ADD COLUMN actualizado_por           bigint        REFERENCES usuario(id),
    ADD CONSTRAINT producto_modalidad_valida CHECK (modalidad IN ('MINORISTA', 'MAYORISTA', 'AMBAS')),
    ADD CONSTRAINT producto_precio_mayorista_positivo CHECK (precio_mayorista IS NULL OR precio_mayorista > 0),
    -- CCPIP-132: el precio mayorista es menor que el minorista y se aplica desde un minimo
    ADD CONSTRAINT producto_mayorista_menor_que_minorista CHECK (
        precio_mayorista IS NULL OR precio IS NULL OR precio_mayorista < precio),
    ADD CONSTRAINT producto_minimo_mayorista_valido CHECK (
        cantidad_minima_mayorista IS NULL OR cantidad_minima_mayorista >= 2);

COMMENT ON COLUMN producto.precio IS 'Precio minorista por unidad. Nulo en borradores y en productos solo mayoristas. RNF-13: los pedidos copian el precio; editarlo no los altera. CCPIP-87: no existen descuentos ni precios promocionales.';
COMMENT ON COLUMN producto.contenido IS 'Contenido por unidad que se muestra en la ficha, p. ej. "250 ml" (PW-04).';
COMMENT ON COLUMN producto.descripcion_propuesta IS 'CCPIP-48 y RNF-29: ultimo texto propuesto por el asistente de IA. Nunca se publica directo: el productor lo acepta o lo reescribe en descripcion.';
COMMENT ON COLUMN producto.linea_id IS 'Linea principal (la que muestra la tarjeta). Todas las lineas del producto estan en producto_linea.';

-- CCPIP-45: un producto puede estar en mas de una linea (p. ej. aceite y superfruto).
CREATE TABLE producto_linea (
    producto_id bigint   NOT NULL REFERENCES producto(id),
    linea_id    smallint NOT NULL REFERENCES linea_producto(id),
    PRIMARY KEY (producto_id, linea_id)
);

CREATE INDEX producto_linea_linea_idx ON producto_linea (linea_id);

INSERT INTO producto_linea (producto_id, linea_id)
SELECT id, linea_id FROM producto WHERE linea_id IS NOT NULL;

-- Los productos sembrados ya estaban publicados
UPDATE producto SET publicado_en = creado_en WHERE estado = 'PUBLICADO' AND publicado_en IS NULL;

CREATE INDEX producto_publicado_en_idx ON producto (publicado_en DESC) WHERE estado = 'PUBLICADO';
