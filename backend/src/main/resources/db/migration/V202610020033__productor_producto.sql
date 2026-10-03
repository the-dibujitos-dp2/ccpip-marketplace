-- V2: productores y catalogo
-- Cubre CCPIP-53 (detalle), CCPIP-152 (historia de origen), CCPIP-153 y CCPIP-154
-- (listado y perfil de productor), CCPIP-52 (busqueda) y CCPIP-38 (sello verificado).
--
-- No hay postulacion de productor (decision del equipo del 1 de octubre).
-- origen_verificado es un atributo que la camara siembra, no el resultado de un flujo.

CREATE TABLE linea_producto (
    id              smallserial  PRIMARY KEY,
    codigo          varchar(40)  NOT NULL UNIQUE,
    nombre          varchar(80)  NOT NULL,
    activo          boolean      NOT NULL DEFAULT true
);

-- RNF-32: catalogo base comun y cerrado. El productor no crea unidades, las solicita
-- al administrador (CCPIP-119 y CCPIP-137, Sprint 1).
CREATE TABLE unidad_medida (
    id              smallserial  PRIMARY KEY,
    codigo          varchar(20)  NOT NULL UNIQUE,
    nombre          varchar(60)  NOT NULL
);

CREATE TABLE productor (
    id              bigserial    PRIMARY KEY,
    usuario_id      bigint       NOT NULL UNIQUE REFERENCES usuario(id),
    comunidad       varchar(160) NOT NULL,
    region          varchar(80)  NOT NULL,
    historia        text,
    origen_verificado boolean    NOT NULL DEFAULT false,
    activo          boolean      NOT NULL DEFAULT true,
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    actualizado_en  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX productor_region_idx ON productor (region);

COMMENT ON COLUMN productor.origen_verificado IS 'CCPIP-38: sello de origen verificado por la camara. Entra sembrado. No existe flujo de postulacion ni de aprobacion en la plataforma.';
COMMENT ON COLUMN productor.historia IS 'Historia de la comunidad, distinta de la historia de cada producto.';

CREATE TABLE producto (
    id              bigserial    PRIMARY KEY,
    productor_id    bigint       NOT NULL REFERENCES productor(id),
    nombre          varchar(200) NOT NULL,
    descripcion     text,
    historia_origen text,
    linea_id        smallint     NOT NULL REFERENCES linea_producto(id),
    unidad_medida_id smallint    NOT NULL REFERENCES unidad_medida(id),
    precio          numeric(10,2) NOT NULL,
    es_materia_prima boolean     NOT NULL DEFAULT false,
    estado          varchar(20)  NOT NULL DEFAULT 'BORRADOR',
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT producto_precio_positivo CHECK (precio > 0),
    CONSTRAINT producto_estado_valido CHECK (estado IN ('BORRADOR', 'PUBLICADO', 'RETIRADO'))
);

CREATE INDEX producto_productor_idx ON producto (productor_id);
CREATE INDEX producto_linea_idx ON producto (linea_id);
CREATE INDEX producto_estado_idx ON producto (estado);

-- RNF-22: la busqueda es insensible a mayusculas y a tildes.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent() es STABLE, no IMMUTABLE, porque depende del diccionario activo, y
-- Postgres no admite funciones STABLE en una expresion de indice. El envoltorio
-- fija el diccionario de forma explicita, lo que la vuelve inmutable de verdad.
-- Sin esto, "CREATE INDEX ... lower(unaccent(nombre))" falla con
-- "functions in index expression must be marked IMMUTABLE".
CREATE OR REPLACE FUNCTION inmutable_unaccent(text)
RETURNS text AS $$
    SELECT public.unaccent('public.unaccent', $1)
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

CREATE INDEX producto_busqueda_idx ON producto (lower(inmutable_unaccent(nombre)));

-- La consulta de busqueda tiene que usar la misma expresion para que el indice sirva:
--   WHERE lower(inmutable_unaccent(nombre)) LIKE lower(inmutable_unaccent(:termino)) || '%'

COMMENT ON COLUMN producto.estado IS 'RNF-17: no existe borrado de productos. El retiro es logico via estado, para preservar el historico de compras. Los tres estados son los de CCPIP-160.';
COMMENT ON COLUMN producto.historia_origen IS 'CCPIP-152: el relato que acompana al producto. Es el diferenciador de la plataforma.';
COMMENT ON COLUMN producto.precio IS 'Precio minorista. El precio mayorista entra en Sprint 1 con CCPIP-132, en su propia columna o tabla.';
