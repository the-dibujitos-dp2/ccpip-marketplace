-- CCPIP-178 y CCPIP-370 (RF-153, RF-260): compra minorista y mayorista nacional conforme a las reglas
-- aprobadas por el cliente el 05/11/2026 (CCPIP-141): esta version vende solo en Peru y en soles; la
-- venta internacional (CCPIP-371, 372) queda para una siguiente fase. Toda entrega es nacional: los
-- puntos de acopio y los distritos de destino salen del catalogo territorial del INEI.
--
-- La moneda queda explicita en el pedido y en cada intento de pago, y la base no admite otra: un cobro
-- en otra moneda exige antes una decision del cliente (C9) y una migracion.

ALTER TABLE pedido
    ADD COLUMN moneda char(3) NOT NULL DEFAULT 'PEN',
    ADD CONSTRAINT pedido_moneda_soles CHECK (moneda = 'PEN');

ALTER TABLE pago
    ADD COLUMN moneda char(3) NOT NULL DEFAULT 'PEN',
    ADD CONSTRAINT pago_moneda_soles CHECK (moneda = 'PEN');

COMMENT ON COLUMN pedido.moneda IS
    'ISO 4217. Solo PEN (soles): decision del cliente del 05/11/2026, CCPIP-141. Montos, envio y comision en esta moneda.';
COMMENT ON COLUMN pago.moneda IS
    'ISO 4217 del cobro enviado a la pasarela; la del pedido. Solo PEN (CCPIP-178, CCPIP-370).';
