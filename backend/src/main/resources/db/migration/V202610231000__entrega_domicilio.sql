-- CCPIP-59 (RF-40): el comprador elige entrega a domicilio desde un punto de acopio habilitado.
-- Sigue el modelo de datos del proyecto (paquete Entrega y territorio): ubigeo, cobertura_entrega y
-- entrega. Entra solo lo que esta eleccion necesita; la declaracion y edicion de la cobertura por el
-- punto (CCPIP-149, 401, 402, 403), la validacion previa al pago (CCPIP-60), las direcciones guardadas
-- (CCPIP-61, 65, 393 a 395) y el reparto (CCPIP-107 a 110) llegan con sus historias.
--
-- El costo del envio no se modela: depende de una definicion pendiente del cliente (CCPIP-101), y el
-- total del pedido sigue siendo la suma de sus lineas.

CREATE TABLE ubigeo (
    codigo          char(6)      PRIMARY KEY,
    departamento    varchar(80)  NOT NULL,
    provincia       varchar(80)  NOT NULL,
    distrito        varchar(80)  NOT NULL,
    CONSTRAINT ubigeo_codigo_valido CHECK (codigo ~ '^[0-9]{6}$')
);

COMMENT ON TABLE ubigeo IS 'Catalogo territorial de departamentos, provincias y distritos con el codigo de seis digitos del INEI. Provisional: lo completa el catalogo territorial aprobado por el cliente (CCPIP-149).';

CREATE TABLE cobertura_entrega (
    id              bigserial    PRIMARY KEY,
    ubicacion_id    bigint       NOT NULL REFERENCES ubicacion(id),
    ubigeo          char(6)      NOT NULL REFERENCES ubigeo(codigo),
    activa          boolean      NOT NULL DEFAULT true,
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT cobertura_entrega_unica UNIQUE (ubicacion_id, ubigeo)
);

CREATE INDEX cobertura_entrega_distrito_idx ON cobertura_entrega (ubigeo) WHERE activa;

COMMENT ON TABLE cobertura_entrega IS 'Distritos que atiende cada punto de acopio para la entrega a domicilio. Desactivar una zona no borra su historial.';
COMMENT ON COLUMN cobertura_entrega.ubicacion_id IS 'Siempre un PUNTO_ACOPIO: el productor no entrega al consumidor, lo hace el punto de acopio.';

CREATE TABLE entrega (
    id                bigserial    PRIMARY KEY,
    pedido_id         bigint       NOT NULL UNIQUE REFERENCES pedido(id),
    distribuidor_id   bigint       REFERENCES usuario(id),
    ubigeo            char(6)      NOT NULL REFERENCES ubigeo(codigo),
    direccion         varchar(255) NOT NULL,
    referencia        varchar(255),
    contacto_nombre   varchar(160) NOT NULL,
    contacto_celular  varchar(20)  NOT NULL,
    estado            varchar(12)  NOT NULL DEFAULT 'DISPONIBLE',
    asignada_en       timestamptz,
    retirada_en       timestamptz,
    entregada_en      timestamptz,
    creado_en         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT entrega_estado_valido CHECK (estado IN ('DISPONIBLE', 'ASIGNADA', 'RETIRADA', 'EN_CAMINO', 'ENTREGADA')),
    CONSTRAINT entrega_celular_valido CHECK (contacto_celular ~ '^9[0-9]{8}$')
);

CREATE INDEX entrega_estado_idx ON entrega (estado);

COMMENT ON TABLE entrega IS 'Entrega a domicilio de un pedido con modalidad ENVIO: una por pedido. El pedido sale siempre del punto de acopio de pedido.ubicacion_origen_id.';
COMMENT ON COLUMN entrega.ubigeo IS 'Distrito de destino; debe estar en la cobertura activa del punto de origen al crear el pedido.';
COMMENT ON COLUMN entrega.distribuidor_id IS 'Nulo hasta que un distribuidor asume la entrega (CCPIP-107 a 110).';
COMMENT ON COLUMN entrega.contacto_celular IS 'Celular de quien recibe, normalizado a 9 digitos.';
COMMENT ON COLUMN pedido.modalidad IS 'RECOJO en el punto de acopio, o ENVIO a domicilio (CCPIP-59): un pedido ENVIO tiene su fila en entrega.';
