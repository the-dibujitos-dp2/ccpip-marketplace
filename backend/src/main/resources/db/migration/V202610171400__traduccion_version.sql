-- CCPIP-170 (RF-164): cada traduccion queda vinculada a su contenido original y a la version de ese
-- original desde la que se tradujo. CCPIP-373 (RF-263): cuando el original cambia, sus traducciones
-- quedan DESACTUALIZADAS (no se borran) y el catalogo vuelve a mostrar el original. CCPIP-147
-- (RF-168): se registra si la traduccion es automatica y de que proveedor salio. RNF-33 y RNF-34.

-- Version vigente de cada campo original. Sin fila, el campo esta en su version 1: los textos que ya
-- existian no necesitan una fila hasta su primera edicion.
CREATE TABLE contenido_version (
    entidad        varchar(20)  NOT NULL,
    clave          varchar(60)  NOT NULL,
    campo          varchar(40)  NOT NULL,
    version        integer      NOT NULL,
    actualizado_en timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (entidad, clave, campo),
    CONSTRAINT contenido_version_entidad_valida CHECK (entidad IN ('PRODUCTO', 'HISTORIA', 'TIENDA', 'COMUNIDAD', 'LINEA', 'UNIDAD')),
    CONSTRAINT contenido_version_positiva CHECK (version >= 1)
);

COMMENT ON TABLE contenido_version IS 'CCPIP-170/373: version de cada campo original traducible. Sube en 1 cada vez que el productor cambia ese texto; sin fila es la version 1.';

ALTER TABLE traduccion
    -- COMUNIDAD: la escribio o la reviso una persona (productor o Camara). AUTOMATICA: salio de un proveedor
    ADD COLUMN origen           varchar(12) NOT NULL DEFAULT 'COMUNIDAD',
    ADD COLUMN proveedor        varchar(40),
    ADD COLUMN version_original integer     NOT NULL DEFAULT 1,
    ADD COLUMN desactualizada_en timestamptz,
    ADD CONSTRAINT traduccion_origen_valido CHECK (origen IN ('COMUNIDAD', 'AUTOMATICA')),
    ADD CONSTRAINT traduccion_automatica_con_proveedor CHECK (origen <> 'AUTOMATICA' OR proveedor IS NOT NULL),
    ADD CONSTRAINT traduccion_version_positiva CHECK (version_original >= 1),
    ADD CONSTRAINT traduccion_desactualizada_con_fecha CHECK ((estado = 'DESACTUALIZADA') = (desactualizada_en IS NOT NULL));

ALTER TABLE traduccion DROP CONSTRAINT traduccion_estado_valido;
ALTER TABLE traduccion ALTER COLUMN estado TYPE varchar(14);
ALTER TABLE traduccion ADD CONSTRAINT traduccion_estado_valido CHECK (estado IN ('PENDIENTE', 'APROBADA', 'DESACTUALIZADA'));

-- El catalogo lee las aprobadas y las desactualizadas que alguna vez se publicaron (para avisar que
-- se muestra el original)
DROP INDEX traduccion_aprobada_idx;
CREATE INDEX traduccion_publicable_idx ON traduccion (entidad, idioma, clave) WHERE estado IN ('APROBADA', 'DESACTUALIZADA');

COMMENT ON COLUMN traduccion.origen IS 'CCPIP-171: COMUNIDAD (escrita o revisada por una persona) o AUTOMATICA (CCPIP-147). La ficha la etiqueta segun el origen.';
COMMENT ON COLUMN traduccion.proveedor IS 'CCPIP-147: proveedor que propuso la traduccion automatica. No se impone ninguno (RF-168).';
COMMENT ON COLUMN traduccion.version_original IS 'CCPIP-170: version del campo original (contenido_version) desde la que se tradujo.';
COMMENT ON COLUMN traduccion.desactualizada_en IS 'CCPIP-373: cuando cambio el original. La traduccion se conserva pero ya no se publica.';
