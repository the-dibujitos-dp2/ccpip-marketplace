-- CCPIP-49 (RF-73): el productor edita el borrador de descripcion que propone la IA. Se registra la aceptacion
-- (quien y cuando) y se conserva la version sugerida junto a la final (producto.descripcion), como ya ocurre con la
-- historia (producto_historia.texto_propuesto y texto_final). descripcion_propuesta sigue siendo el borrador
-- pendiente: pedir otra version o descartarla (CCPIP-280) ya no borra la sugerencia que el productor acepto.

ALTER TABLE producto
    ADD COLUMN descripcion_sugerida      text,
    ADD COLUMN descripcion_aceptada_en   timestamptz,
    ADD COLUMN descripcion_aceptada_por  bigint REFERENCES usuario(id),
    ADD CONSTRAINT producto_descripcion_aceptada_completa CHECK (
        (descripcion_sugerida IS NULL) = (descripcion_aceptada_en IS NULL)
        AND (descripcion_aceptada_en IS NULL) = (descripcion_aceptada_por IS NULL));

-- Antes la aceptacion no se registraba: una descripcion asistida con su propuesta guardada se tomo de ella al
-- guardar. Es la mejor evidencia que hay; la fecha y el responsable son los de la ultima edicion del producto.
UPDATE producto p
SET descripcion_sugerida = p.descripcion_propuesta,
    descripcion_propuesta = NULL,
    descripcion_aceptada_en = p.actualizado_en,
    descripcion_aceptada_por = coalesce(p.actualizado_por, p.creado_por,
                                        (SELECT pr.usuario_id FROM productor pr WHERE pr.id = p.productor_id))
WHERE p.descripcion_asistida AND p.descripcion_propuesta IS NOT NULL;

COMMENT ON COLUMN producto.descripcion_propuesta IS 'CCPIP-48 y RNF-29: borrador de la IA pendiente de decision. Nunca se publica directo: el productor lo acepta (pasa a descripcion_sugerida, CCPIP-49) o lo descarta (CCPIP-280).';
COMMENT ON COLUMN producto.descripcion_sugerida IS 'CCPIP-49: la sugerencia de la IA que el productor acepto, tal como la propuso. La version final, editada por el productor, es descripcion.';
COMMENT ON COLUMN producto.descripcion_aceptada_en IS 'CCPIP-49: cuando el productor acepto la sugerencia. Cada aceptacion queda ademas en producto_auditoria.';
COMMENT ON COLUMN producto.descripcion_aceptada_por IS 'CCPIP-49: quien acepto la sugerencia.';
