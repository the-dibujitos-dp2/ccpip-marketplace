-- Mejoras de laboratorio (distribuidor): cada momento de la entrega queda en la entrega. Asignada, retirada y
-- entregada ya tenian su columna; en camino solo quedaba en el historial del pedido.
ALTER TABLE entrega ADD COLUMN en_camino_en timestamptz;

-- Las que ya salieron toman la hora del historial del pedido
UPDATE entrega e
SET en_camino_en = (SELECT min(h.registrado_en) FROM pedido_estado_historial h
                    WHERE h.pedido_id = e.pedido_id AND h.estado = 'EN_CAMINO')
WHERE e.estado IN ('EN_CAMINO', 'ENTREGADA');

COMMENT ON COLUMN entrega.en_camino_en IS 'Cuando el distribuidor salio a entregarla (CCPIP-318). Nulo hasta entonces.';
