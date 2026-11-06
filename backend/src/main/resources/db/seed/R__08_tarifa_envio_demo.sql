-- CCPIP-482: tarifas de envio de ejemplo para la demo, en los distritos con cobertura demo (R__06). Los montos
-- reales los fija la Camara desde la administracion. Idempotente (RNF-28): no pisa una tarifa ya configurada.

INSERT INTO tarifa_envio (ubigeo, monto)
SELECT DISTINCT c.ubigeo, CASE WHEN c.ubigeo LIKE '15%' THEN 10.00 ELSE 12.00 END
FROM cobertura_entrega c
ON CONFLICT (ubigeo) DO NOTHING;
