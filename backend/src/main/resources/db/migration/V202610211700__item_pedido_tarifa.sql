-- CCPIP-345 (RF-235): los reportes de la plataforma separan las ventas B2C (tarifa minorista) de las
-- B2B (tarifa mayorista, CCPIP-132 y CCPIP-134). La tarifa se decide en el carrito segun la modalidad
-- de la oferta y la cantidad (Tarifas.resolver); hasta ahora el pedido copiaba el precio pero no cual
-- de las dos tarifas era. Desde esta migracion la compra la copia en cada linea (RNF-13).

ALTER TABLE item_pedido
    ADD COLUMN tarifa varchar(10) NOT NULL DEFAULT 'MINORISTA',
    ADD CONSTRAINT item_pedido_tarifa_valida CHECK (tarifa IN ('MINORISTA', 'MAYORISTA'));

COMMENT ON COLUMN item_pedido.tarifa IS
    'MINORISTA (B2C) o MAYORISTA (B2B): la tarifa con la que se vendio la linea, copiada al crear el pedido. CCPIP-345.';

-- Lineas anteriores: se reconoce la tarifa mayorista porque el precio copiado es el mayorista de la
-- oferta y distinto del minorista. Es la mejor reconstruccion posible; desde ahora se copia al comprar.
-- La columna no esta entre los importes que el disparador item_pedido_comision_congelada protege.
UPDATE item_pedido i
SET tarifa = 'MAYORISTA'
FROM producto p
WHERE p.id = i.producto_id
  AND p.precio_mayorista IS NOT NULL
  AND i.precio_unitario = p.precio_mayorista
  AND (p.precio IS NULL OR p.precio <> p.precio_mayorista);
