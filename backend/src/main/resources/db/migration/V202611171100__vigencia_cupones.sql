-- CCPIP-89: un cupon del catalogo se puede canjear dentro de su periodo (fechas de Lima, inclusive), como las campanas:
-- programado antes de empezar, activo mientras rige y finalizado al terminar o si el sub administrador lo finaliza.
ALTER TABLE cupon ADD COLUMN disponible_desde date NOT NULL DEFAULT (now() AT TIME ZONE 'America/Lima')::date;
ALTER TABLE cupon ADD COLUMN disponible_hasta date;
ALTER TABLE cupon ADD CONSTRAINT cupon_periodo CHECK (disponible_hasta IS NULL OR disponible_hasta >= disponible_desde);

COMMENT ON COLUMN cupon.disponible_hasta IS 'Ultimo dia en que se puede canjear; nulo = sin fecha de fin.';
