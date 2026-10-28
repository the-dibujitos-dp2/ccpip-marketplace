-- CCPIP-29 (RF-04): el productor solicita expresamente la autorizacion de un producto. Adjuntar evidencias ya
-- no la envia a revision: la deja en BORRADOR hasta que el productor la solicite. Alta de cuenta,
-- autorizacion y publicacion siguen separadas (C1). Las solicitudes ya enviadas no cambian.

ALTER TABLE autorizacion_producto DROP CONSTRAINT autorizacion_estado_valido;
ALTER TABLE autorizacion_producto ADD CONSTRAINT autorizacion_estado_valido
    CHECK (estado IN ('BORRADOR', 'SOLICITADA', 'APROBADA', 'RECHAZADA', 'REVOCADA'));

COMMENT ON COLUMN autorizacion_producto.solicitada_en IS
    'CCPIP-29: cuando el productor la solicito (o volvio a solicitarla). Nula en BORRADOR. Ordena la bandeja S-01.';
