-- Tienda de la comunidad (CCPIP-30, CCPIP-56, CCPIP-153, CCPIP-154).
-- La tienda vive en la misma fila del productor: un productor tiene exactamente una tienda,
-- y separarla en otra tabla solo obligaria a un join en cada consulta del catalogo.
-- La camara incorpora al productor con su comunidad y region (sin postulacion, decision del
-- 1 de octubre); el productor completa la tienda y la publica desde PW-08.

ALTER TABLE productor
    ADD COLUMN nombre_tienda       varchar(120),
    ADD COLUMN descripcion_corta   varchar(200),
    ADD COLUMN cita                varchar(200),
    ADD COLUMN pueblo              varchar(80),
    ADD COLUMN localidad           varchar(80),
    ADD COLUMN socios              integer,
    ADD COLUMN verificado_en       date,
    ADD COLUMN tienda_publicada    boolean      NOT NULL DEFAULT false,
    ADD COLUMN tienda_publicada_en timestamptz,
    ADD CONSTRAINT productor_socios_positivo CHECK (socios IS NULL OR socios > 0),
    -- Una tienda publicada siempre tiene nombre: es lo primero que ve el comprador
    ADD CONSTRAINT productor_tienda_publicada_con_nombre CHECK (NOT tienda_publicada OR nombre_tienda IS NOT NULL);

CREATE INDEX productor_tienda_publicada_idx ON productor (tienda_publicada) WHERE tienda_publicada;

COMMENT ON COLUMN productor.nombre_tienda IS 'CCPIP-30: nombre con el que la comunidad se presenta, p. ej. "Asoc. Nii Joi".';
COMMENT ON COLUMN productor.descripcion_corta IS 'Una o dos lineas bajo el nombre de la tienda (PW-08).';
COMMENT ON COLUMN productor.cita IS 'Cita de la comunidad que encabeza su historia (CW-04).';
COMMENT ON COLUMN productor.pueblo IS 'Pueblo indigena de pertenencia, p. ej. Shipibo-Konibo (CCPIP-30).';
COMMENT ON COLUMN productor.tienda_publicada IS 'La tienda solo es publica cuando el productor la publica desde PW-08.';
