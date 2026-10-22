-- CCPIP-85 (RF-78): el subadministrador bloquea las ventas de un productor indicando motivo.
-- CCPIP-322 (RF-215): las desbloquea, tambien con motivo.
--
-- El bloqueo es una decision de gobernanza (Figma S-03 y S-04, desde las denuncias), distinta de la
-- revocacion de CCPIP-330, que es de cumplimiento. Por eso tiene su propio estado: desbloquear no
-- restituye una venta revocada ni al reves. El efecto para el comprador es el mismo: sus ofertas siguen
-- visibles en Venta pausada y los pedidos confirmados no cambian. Nada se borra: cada decision es una
-- fila nueva de productor_decision, que es de solo insercion.

ALTER TABLE productor_decision ALTER COLUMN decision TYPE varchar(20);
ALTER TABLE productor_decision DROP CONSTRAINT productor_decision_valida;
ALTER TABLE productor_decision ADD CONSTRAINT productor_decision_valida CHECK (decision IN (
    'HABILITAR_TIENDA', 'REVOCAR_VENTA', 'RESTITUIR_VENTA', 'BLOQUEAR_VENTA', 'DESBLOQUEAR_VENTA'));

COMMENT ON TABLE productor_decision IS 'CCPIP-291, CCPIP-330, CCPIP-85 y CCPIP-322: habilitacion de la tienda, revocacion o restitucion y bloqueo o desbloqueo de la venta del productor, con motivo y responsable. Solo insercion.';

ALTER TABLE productor
    ADD COLUMN venta_bloqueada      boolean NOT NULL DEFAULT false,
    ADD COLUMN bloqueo_decision_id  bigint  REFERENCES productor_decision(id);

COMMENT ON COLUMN productor.venta_bloqueada IS 'CCPIP-85: la venta del productor esta bloqueada por el subadministrador. Sus ofertas siguen visibles en Venta pausada. Independiente de venta_revocada.';
COMMENT ON COLUMN productor.bloqueo_decision_id IS 'Ultimo bloqueo o desbloqueo de la venta del productor.';
