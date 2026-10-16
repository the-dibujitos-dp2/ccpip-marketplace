-- CCPIP-90: comision de ejemplo para la demo (10 %), registrada por el superadministrador de
-- prueba. Solo si nunca se configuro ninguna: si la Camara ya fijo la suya, esta semilla no la toca.
-- Idempotente (RNF-28). El valor real lo define la Camara desde SA-02.

INSERT INTO comision_configuracion (porcentaje, vigente_desde, motivo, registrado_por)
SELECT 10.00, DATE '2026-01-01', 'Valor de ejemplo para la demostración; la Cámara define el definitivo.', u.id
FROM usuario u
WHERE u.correo = 'sudo@ccpip.test'
  AND NOT EXISTS (SELECT 1 FROM comision_configuracion);
