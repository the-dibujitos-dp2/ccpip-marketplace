-- CCPIP-336: asociación opcional (compatibilidad histórica) de certificado a un lote del producto.
ALTER TABLE lote ADD CONSTRAINT lote_id_producto_unico UNIQUE (id, producto_id);
ALTER TABLE evidencia_producto ADD COLUMN lote_id bigint;
ALTER TABLE evidencia_producto ADD CONSTRAINT evidencia_lote_mismo_producto
    FOREIGN KEY (lote_id, producto_id) REFERENCES lote (id, producto_id);
COMMENT ON COLUMN evidencia_producto.lote_id IS 'CCPIP-336: lote opcional; NULL conserva evidencias anteriores y flujos existentes.';
