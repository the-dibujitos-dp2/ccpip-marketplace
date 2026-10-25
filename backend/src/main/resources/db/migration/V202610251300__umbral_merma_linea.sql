-- CCPIP-430 (mejora del laboratorio del 22/10): el tablero del superadministrador (SA-01) colorea la
-- merma del mes de cada punto de acopio segun el nivel de perdida. Cada linea del catalogo maestro tiene
-- sus umbrales, porque lo que es normal depende de que tan perecible es lo que agrupa (la fruta se pierde
-- mas que el grano). Acuerdo del equipo del 25/10: umbrales por linea, editables por el superadministrador
-- en Catalogo maestro > Lineas, que nacen en 5 % (alerta) y 10 % (critico). No hay un valor escondido:
-- toda linea, nueva o existente, tiene los suyos a la vista.
--
-- % de merma de una linea en un punto = merma del mes / (merma del mes + stock vigente). Debajo de la
-- alerta es normal; desde la alerta, alerta; desde el critico, critico.

ALTER TABLE linea_producto
    ADD COLUMN merma_umbral_alerta  numeric(4,1) NOT NULL DEFAULT 5,
    ADD COLUMN merma_umbral_critico numeric(4,1) NOT NULL DEFAULT 10,
    ADD CONSTRAINT linea_producto_umbrales_merma_validos
        CHECK (merma_umbral_alerta > 0 AND merma_umbral_alerta < merma_umbral_critico AND merma_umbral_critico <= 100);

COMMENT ON COLUMN linea_producto.merma_umbral_alerta IS
    'CCPIP-430: % de merma del mes desde el que la linea se muestra en alerta (amarillo) en el tablero SA-01.';
COMMENT ON COLUMN linea_producto.merma_umbral_critico IS
    'CCPIP-430: % de merma del mes desde el que la linea se muestra en estado critico (rojo) en el tablero SA-01.';
