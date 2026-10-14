-- CCPIP-166, CCPIP-357 y CCPIP-358 (RF-116, RF-247, RF-248): el contenido aprobado de productos,
-- tiendas y comunidades en espanol e ingles. El espanol es el original y vive en su propia tabla;
-- aqui solo va su version en ingles, campo por campo, y solo se publica la aprobada.
--
-- Adelanta el modelo de CCPIP-170 (almacenar una traduccion vinculada a su contenido original).
-- Quien traduce (productor, Camara o traduccion asistida) sigue pendiente de definir con el
-- cliente: por eso cada fila nace PENDIENTE y se publica al aprobarse.

CREATE TABLE traduccion (
    id             bigserial    PRIMARY KEY,
    -- Que contenido traduce: PRODUCTO y HISTORIA por id de producto, TIENDA y COMUNIDAD por id de
    -- productor (la tienda vive en la fila del productor), LINEA y UNIDAD por su codigo.
    entidad        varchar(20)  NOT NULL,
    clave          varchar(60)  NOT NULL,
    campo          varchar(40)  NOT NULL,
    idioma         varchar(2)   NOT NULL,
    texto          text         NOT NULL,
    estado         varchar(10)  NOT NULL DEFAULT 'PENDIENTE',
    aprobada_en    timestamptz,
    creado_en      timestamptz  NOT NULL DEFAULT now(),
    actualizado_en timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT traduccion_entidad_valida CHECK (entidad IN ('PRODUCTO', 'HISTORIA', 'TIENDA', 'COMUNIDAD', 'LINEA', 'UNIDAD')),
    -- El espanol nunca se traduce: es el original
    CONSTRAINT traduccion_idioma_valido CHECK (idioma = 'en'),
    CONSTRAINT traduccion_estado_valido CHECK (estado IN ('PENDIENTE', 'APROBADA')),
    CONSTRAINT traduccion_aprobada_con_fecha CHECK (estado <> 'APROBADA' OR aprobada_en IS NOT NULL),
    CONSTRAINT traduccion_texto_no_vacio CHECK (btrim(texto) <> ''),
    CONSTRAINT traduccion_unica UNIQUE (entidad, clave, campo, idioma)
);

-- El catalogo solo lee traducciones aprobadas, por entidad y clave
CREATE INDEX traduccion_aprobada_idx ON traduccion (entidad, idioma, clave) WHERE estado = 'APROBADA';

COMMENT ON TABLE traduccion IS 'CCPIP-166/357/358: version en ingles del contenido, campo por campo. Solo la APROBADA se muestra; si falta, se muestra el original en espanol.';
COMMENT ON COLUMN traduccion.clave IS 'Id del producto o del productor, o codigo de la linea o unidad, segun la entidad.';
COMMENT ON COLUMN traduccion.campo IS 'Campo traducido: nombre, descripcion, titulo, texto, historia, cita, o una parte de la historia (comunidad, cultivo, proceso, significado).';
