-- CCPIP-172 (RF-144): producto.id identifica una oferta concreta de un productor.
-- Ofertas del mismo nombre no se agrupan. Cambiar de productor exige elegir otra oferta.
CREATE FUNCTION conservar_productor_oferta() RETURNS trigger AS $$
BEGIN
    IF NEW.productor_id IS DISTINCT FROM OLD.productor_id THEN
        RAISE EXCEPTION 'El productor de una oferta no se puede sustituir; crea otra oferta.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER producto_conservar_productor
    BEFORE UPDATE OF productor_id ON producto
    FOR EACH ROW EXECUTE FUNCTION conservar_productor_oferta();

-- El productor copiado al pedido debe corresponder a la oferta elegida.
ALTER TABLE producto ADD CONSTRAINT producto_oferta_productor_unico UNIQUE (id, productor_id);
ALTER TABLE item_pedido ADD CONSTRAINT item_pedido_oferta_productor_fk
    FOREIGN KEY (producto_id, productor_id) REFERENCES producto (id, productor_id);

COMMENT ON COLUMN producto.productor_id IS
    'CCPIP-172: productor inmutable de esta oferta, desde catalogo y carrito hasta pago e inventario.';
