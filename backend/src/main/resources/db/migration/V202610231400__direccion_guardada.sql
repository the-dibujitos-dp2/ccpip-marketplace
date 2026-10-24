-- CCPIP-65 (RF-46): el comprador guarda sus direcciones de entrega habituales. Sigue el modelo de datos del
-- proyecto (tabla direccion_guardada). Entra solo lo que guardar necesita; consultar (CCPIP-393), modificar
-- (CCPIP-394) y retirar (CCPIP-395) llegan con sus historias y se apoyan en esta tabla.

CREATE TABLE direccion_guardada (
    id          bigserial     PRIMARY KEY,
    usuario_id  bigint        NOT NULL REFERENCES usuario(id),
    alias       varchar(40),
    ubigeo      char(6)       NOT NULL REFERENCES ubigeo(codigo),
    direccion   varchar(255)  NOT NULL,
    referencia  varchar(255),
    activa      boolean       NOT NULL DEFAULT true,
    creado_en   timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX direccion_guardada_usuario_idx ON direccion_guardada (usuario_id) WHERE activa;

COMMENT ON TABLE direccion_guardada IS 'Direcciones habituales del comprador. Retirarla la marca inactiva (CCPIP-395); los pedidos copian su destino en entrega y no dependen de esta tabla.';
COMMENT ON COLUMN direccion_guardada.usuario_id IS 'Siempre el comprador de la sesion: nunca se recibe de la peticion (RNF-05).';
COMMENT ON COLUMN direccion_guardada.ubigeo IS 'Debe ser un distrito del catalogo territorial: un destino admitido.';
