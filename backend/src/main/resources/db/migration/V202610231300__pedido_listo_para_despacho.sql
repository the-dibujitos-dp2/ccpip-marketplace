-- CCPIP-107 (RF-54): el distribuidor consulta los pedidos preparados disponibles para distribucion. Un pedido
-- a domicilio preparado en el punto de acopio queda LISTO_PARA_DESPACHO (modelo de datos del proyecto), a
-- diferencia del de recojo, que queda LISTO_PARA_RECOJO. Entra solo el estado que esta consulta necesita; quien
-- lo marca (CCPIP-320) y los estados de camino (CCPIP-318) llegan con sus historias.

ALTER TABLE pedido DROP CONSTRAINT pedido_estado_valido;
ALTER TABLE pedido ADD CONSTRAINT pedido_estado_valido CHECK (estado IN (
    'PENDIENTE_PAGO', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'LISTO_PARA_DESPACHO', 'ENTREGADO', 'CANCELADO'));

COMMENT ON COLUMN pedido.estado IS 'LISTO_PARA_DESPACHO: un pedido ENVIO preparado, disponible para que un distribuidor lo asuma (CCPIP-107). LISTO_PARA_RECOJO es el equivalente de un pedido RECOJO.';
