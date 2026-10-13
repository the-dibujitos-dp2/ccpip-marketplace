-- CCPIP-71 (RF-53): registrar el recojo del pedido por el comprador o la persona autorizada.
-- Entra solo lo que el recojo necesita. La confirmacion de la compra (CCPIP-157) llena la persona
-- autorizada y el celular al confirmar; el recojo los valida y deja constancia de quien recogio.

-- Quien puede recoger ademas del comprador, y el celular con el que se valida (RF-126: "registrar
-- persona autorizada y celular"). Nulos: recoge el comprador, o el pedido aun no registra contacto.
ALTER TABLE pedido ADD COLUMN persona_autorizada varchar(160);
ALTER TABLE pedido ADD COLUMN celular_contacto   varchar(20);

-- Constancia del recojo: quien se llevo el pedido y con que celular se verifico. El responsable del
-- punto y la fecha ya quedan en pedido_estado_historial (estado ENTREGADO).
ALTER TABLE pedido ADD COLUMN recogido_por       varchar(160);
ALTER TABLE pedido ADD COLUMN recogido_celular   varchar(20);

ALTER TABLE pedido ADD CONSTRAINT pedido_recojo_completo
    CHECK ((recogido_por IS NULL) = (recogido_celular IS NULL));
ALTER TABLE pedido ADD CONSTRAINT pedido_recojo_entregado
    CHECK (recogido_por IS NULL OR estado = 'ENTREGADO');

COMMENT ON COLUMN pedido.persona_autorizada IS 'Persona, distinta del comprador, que puede recoger el pedido. Nula si recoge el comprador.';
COMMENT ON COLUMN pedido.celular_contacto   IS 'Celular de contacto de quien recoge, registrado al confirmar la compra. El recojo exige que coincida.';
COMMENT ON COLUMN pedido.recogido_por       IS 'Nombre de quien recogio, tal como figura en el pedido (el comprador o la persona autorizada). Solo con estado ENTREGADO.';
COMMENT ON COLUMN pedido.recogido_celular   IS 'Celular verificado al registrar el recojo, normalizado a 9 digitos.';
