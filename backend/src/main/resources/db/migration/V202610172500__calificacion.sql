-- CCPIP-81 (RF-88): el comprador califica con estrellas los productos de los pedidos que recibio.
-- CCPIP-82 (RF-89): el promedio publico se calcula de esta tabla y solo se muestra con un minimo de
-- calificaciones (ccpip.calificaciones.minimo-para-promedio). Sin calificaciones no hay promedio, ni cero.
--
-- Una calificacion por linea del pedido (item_pedido): comprar 3 unidades en una misma linea cuenta como
-- una sola calificacion. Compra verificada: la base exige que la linea sea del mismo comprador, que el
-- pedido este ENTREGADO y que producto y productor sean los de la linea.

CREATE TABLE calificacion (
    id              bigserial    PRIMARY KEY,
    item_pedido_id  bigint       NOT NULL UNIQUE REFERENCES item_pedido(id),
    pedido_id       bigint       NOT NULL REFERENCES pedido(id),
    producto_id     bigint       NOT NULL,
    productor_id    bigint       NOT NULL REFERENCES productor(id),
    usuario_id      bigint       NOT NULL REFERENCES usuario(id),
    estrellas       smallint     NOT NULL,
    comentario      varchar(500),
    calificado_en   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT calificacion_estrellas_validas CHECK (estrellas BETWEEN 1 AND 5),
    CONSTRAINT calificacion_comentario_no_vacio CHECK (comentario IS NULL OR btrim(comentario) <> ''),
    CONSTRAINT calificacion_oferta_fk FOREIGN KEY (producto_id, productor_id) REFERENCES producto (id, productor_id)
);

CREATE INDEX calificacion_producto_idx ON calificacion (producto_id, calificado_en DESC);
CREATE INDEX calificacion_usuario_idx ON calificacion (usuario_id);

COMMENT ON TABLE calificacion IS 'CCPIP-81: calificacion de una linea de un pedido ENTREGADO por su comprador. Una por linea, solo insercion: no se edita ni se borra.';
COMMENT ON COLUMN calificacion.estrellas IS 'Escala de 1 a 5 estrellas (RF-88: "conservar estrellas como escala de calificacion").';
COMMENT ON COLUMN calificacion.comentario IS 'Opcional. Se publica sin el nombre del comprador.';
COMMENT ON COLUMN calificacion.usuario_id IS 'Comprador que califica. Nunca se expone en la ficha publica ni al productor.';

-- Compra verificada en la base, ademas del servicio: nadie califica una linea ajena o no recibida
CREATE OR REPLACE FUNCTION calificacion_compra_verificada()
RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id
        WHERE i.id = NEW.item_pedido_id AND p.id = NEW.pedido_id AND p.usuario_id = NEW.usuario_id
          AND p.estado = 'ENTREGADO' AND i.producto_id = NEW.producto_id AND i.productor_id = NEW.productor_id
    ) THEN
        RAISE EXCEPTION 'Solo se califica una linea de un pedido entregado al mismo comprador (CCPIP-81).'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER calificacion_verificada BEFORE INSERT ON calificacion
    FOR EACH ROW EXECUTE FUNCTION calificacion_compra_verificada();
CREATE TRIGGER calificacion_sin_update BEFORE UPDATE ON calificacion
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
CREATE TRIGGER calificacion_sin_delete BEFORE DELETE ON calificacion
    FOR EACH ROW EXECUTE FUNCTION solo_insercion();
