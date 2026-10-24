-- CCPIP-320 (RF-202): el administrador del punto de acopio registra la salida de un pedido a domicilio preparado:
-- el pedido queda LISTO_PARA_DESPACHO (diccionario de datos del proyecto), preparado para que lo retire un
-- distribuidor, a diferencia del de recojo, que queda LISTO_PARA_RECOJO. Entra solo ese estado; los estados de
-- camino (CCPIP-318) llegan con su historia.

ALTER TABLE pedido DROP CONSTRAINT pedido_estado_valido;
ALTER TABLE pedido ADD CONSTRAINT pedido_estado_valido CHECK (estado IN (
    'PENDIENTE_PAGO', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'LISTO_PARA_DESPACHO', 'ENTREGADO', 'CANCELADO'));

COMMENT ON COLUMN pedido.estado IS 'LISTO_PARA_DESPACHO: un pedido ENVIO preparado en el punto de acopio, para que lo retire un distribuidor (CCPIP-320). LISTO_PARA_RECOJO es el equivalente de un pedido RECOJO.';
