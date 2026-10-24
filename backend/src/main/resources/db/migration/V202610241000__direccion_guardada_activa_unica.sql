-- CCPIP-394: una direccion activa se identifica por comprador, distrito y texto normalizado. El servicio
-- siempre persiste direccion.strip(); no se reescriben ni eliminan datos historicos durante la migracion.
-- Si una instalacion antigua ya contiene duplicados activos, se detiene con un diagnostico para corregirlos
-- expresamente antes de imponer la invariante.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM direccion_guardada
        WHERE activa
        GROUP BY usuario_id, ubigeo, lower(direccion)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'No se puede crear direccion_guardada_activa_unica_idx: hay direcciones activas duplicadas';
    END IF;
END $$;

CREATE UNIQUE INDEX direccion_guardada_activa_unica_idx
    ON direccion_guardada (usuario_id, ubigeo, lower(direccion))
    WHERE activa;
