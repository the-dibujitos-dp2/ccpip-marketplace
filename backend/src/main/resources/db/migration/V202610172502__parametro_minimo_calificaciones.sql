-- CCPIP-82 (RF-89): "umbral configurable". Las calificaciones minimas para publicar el promedio de una
-- oferta son un parametro de SA-02 ("Resenas minimas"), con el mismo historial auditado que la vida del
-- carrito (CCPIP-142). No se siembra ningun valor: mientras el superadministrador no lo fije rige el
-- provisional ccpip.calificaciones.minimo-para-promedio (application.properties).

ALTER TABLE parametro_plataforma DROP CONSTRAINT parametro_clave_valida;
ALTER TABLE parametro_plataforma ADD CONSTRAINT parametro_clave_valida
    CHECK (clave IN ('CARRITO_VIGENCIA_HORAS', 'CALIFICACIONES_MINIMO_PROMEDIO'));

COMMENT ON COLUMN parametro_plataforma.valor IS
    'CARRITO_VIGENCIA_HORAS: horas desde el ultimo cambio del carrito durante las que se conserva. CALIFICACIONES_MINIMO_PROMEDIO: calificaciones necesarias para publicar el promedio de una oferta (CCPIP-82).';
