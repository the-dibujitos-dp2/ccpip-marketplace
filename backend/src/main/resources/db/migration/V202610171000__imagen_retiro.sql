-- CCPIP-389 y CCPIP-388: una foto de producto ya no se borra. Se retira (deja la ficha y el
-- editor) y se conserva, igual que el archivo en el bucket, para no romper referencias
-- historicas. Al reemplazarla, la retirada apunta a la que tomo su lugar.
-- Portada y galeria de la tienda siguen borrandose: no forman parte de ninguna publicacion.

ALTER TABLE imagen
    ADD COLUMN retirada_en     timestamptz,
    ADD COLUMN retirada_por    bigint REFERENCES usuario(id),
    ADD COLUMN reemplazada_por uuid   REFERENCES imagen(id),
    ADD CONSTRAINT imagen_retiro_completo CHECK ((retirada_en IS NULL) = (retirada_por IS NULL)),
    ADD CONSTRAINT imagen_retiro_solo_producto CHECK (retirada_en IS NULL OR tipo = 'PRODUCTO'),
    ADD CONSTRAINT imagen_retirada_no_principal CHECK (retirada_en IS NULL OR NOT principal),
    ADD CONSTRAINT imagen_reemplazo_retirada CHECK (reemplazada_por IS NULL OR retirada_en IS NOT NULL);

-- Lo que se muestra y se cuenta para el limite de fotos son solo las vigentes
CREATE INDEX imagen_producto_vigente_idx ON imagen (producto_id, orden) WHERE retirada_en IS NULL;

COMMENT ON COLUMN imagen.retirada_en IS 'CCPIP-389: retiro logico. La foto sale de la ficha pero se sigue sirviendo por su id.';
COMMENT ON COLUMN imagen.reemplazada_por IS 'CCPIP-388: la foto que tomo su lugar, con el mismo orden y la misma condicion de principal.';
