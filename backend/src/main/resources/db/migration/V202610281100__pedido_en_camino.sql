-- CCPIP-318: EN_CAMINO pertenece al flujo de entrega a domicilio; conservar todos los estados previos.
ALTER TABLE pedido DROP CONSTRAINT pedido_estado_valido;
ALTER TABLE pedido ADD CONSTRAINT pedido_estado_valido CHECK (estado IN (
    'PENDIENTE_PAGO', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'LISTO_PARA_DESPACHO', 'EN_CAMINO', 'ENTREGADO', 'CANCELADO'));
