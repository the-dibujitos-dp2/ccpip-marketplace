-- Administracion del catalogo maestro (CCPIP-96, 309, 310, 311, 351, 312, 313 y 314; RF-83, RF-191 a
-- RF-196 y RF-241). Se construye sobre producto_maestro de CCPIP-42 (V202610171900).
-- El superadministrador administra las lineas y los productos del catalogo maestro
-- (Figma, indice de SA: "lineas"). El catalogo maestro NO es la oferta comercial: una oferta es una
-- fila de `producto` de un productor, con su propio nombre, precio y estado (CCPIP-172).
--
-- Nada se borra: una linea o un producto maestro se desactiva (RNF-17, RNF-18). Las ofertas, los
-- pedidos y las traducciones que ya lo referencian se conservan tal cual.

-- ---------------------------------------------------------------------------------------
-- Lineas: quien y cuando las cambio, y desde cuando estan inactivas
-- ---------------------------------------------------------------------------------------
ALTER TABLE linea_producto
    ADD COLUMN creado_en      timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN actualizado_en timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN desactivado_en timestamptz;

UPDATE linea_producto SET desactivado_en = now() WHERE NOT activo;

ALTER TABLE linea_producto
    ADD CONSTRAINT linea_producto_desactivacion_completa CHECK (activo = (desactivado_en IS NULL)),
    ADD CONSTRAINT linea_producto_codigo_valido CHECK (codigo ~ '^[A-Z0-9_]{2,40}$'),
    ADD CONSTRAINT linea_producto_nombre_presente CHECK (btrim(nombre) <> ''),
    ADD CONSTRAINT linea_producto_orden_valido CHECK (orden >= 0);

-- Dos lineas no se llaman igual, ni cambiando mayusculas o tildes (RNF-22)
CREATE UNIQUE INDEX linea_producto_nombre_unico ON linea_producto (lower(inmutable_unaccent(nombre)));

-- El codigo es la clave de las traducciones (entidad LINEA) y de las validaciones de imagen: no cambia
CREATE FUNCTION linea_producto_codigo_inmutable() RETURNS trigger AS $$
BEGIN
    IF NEW.codigo IS DISTINCT FROM OLD.codigo THEN
        RAISE EXCEPTION 'El codigo de una linea no cambia (CCPIP-310).' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER linea_producto_conservar_codigo BEFORE UPDATE OF codigo ON linea_producto
    FOR EACH ROW EXECUTE FUNCTION linea_producto_codigo_inmutable();

-- Quien cambie activo (la plataforma o un script) deja la fecha de desactivacion coherente
CREATE FUNCTION catalogo_maestro_fecha_desactivacion() RETURNS trigger AS $$
BEGIN
    IF NEW.activo IS DISTINCT FROM OLD.activo THEN
        NEW.desactivado_en := CASE WHEN NEW.activo THEN NULL ELSE now() END;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER linea_producto_fecha_desactivacion BEFORE UPDATE OF activo ON linea_producto
    FOR EACH ROW EXECUTE FUNCTION catalogo_maestro_fecha_desactivacion();

CREATE FUNCTION catalogo_maestro_sin_borrado() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% no se borra: se desactiva (CCPIP-311, CCPIP-314).', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER linea_producto_sin_delete BEFORE DELETE ON linea_producto
    FOR EACH ROW EXECUTE FUNCTION catalogo_maestro_sin_borrado();

COMMENT ON COLUMN linea_producto.activo IS 'CCPIP-311: una linea inactiva no se ofrece para nuevas ofertas ni productos maestros. Las ofertas que ya la tienen la conservan.';
COMMENT ON COLUMN linea_producto.desactivado_en IS 'Desde cuando esta inactiva. Nula si esta activa.';

-- ---------------------------------------------------------------------------------------
-- Productos del catalogo maestro: la tabla producto_maestro y producto.producto_maestro_id
-- vienen de CCPIP-42 (V202610171900). Aqui se agrega lo que crear, modificar y desactivar
-- desde la plataforma necesitan (CCPIP-312, 313 y 314).
-- ---------------------------------------------------------------------------------------
ALTER TABLE producto_maestro
    ADD COLUMN actualizado_en timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN desactivado_en timestamptz;

UPDATE producto_maestro SET desactivado_en = now() WHERE NOT activo;

ALTER TABLE producto_maestro
    ADD CONSTRAINT producto_maestro_desactivacion_completa CHECK (activo = (desactivado_en IS NULL)),
    ADD CONSTRAINT producto_maestro_codigo_valido CHECK (codigo ~ '^[A-Z0-9][A-Z0-9_-]{1,39}$'),
    ADD CONSTRAINT producto_maestro_nombre_presente CHECK (btrim(nombre) <> '');

-- Un mismo producto no se repite dentro de su linea (sin importar mayusculas ni tildes)
CREATE UNIQUE INDEX producto_maestro_nombre_unico ON producto_maestro (linea_id, lower(inmutable_unaccent(nombre)));
CREATE INDEX producto_maestro_linea_idx ON producto_maestro (linea_id);

-- El codigo identifica al maestro fuera de la plataforma (semillas, integraciones): no cambia
CREATE FUNCTION producto_maestro_codigo_inmutable() RETURNS trigger AS $$
BEGIN
    IF NEW.codigo IS DISTINCT FROM OLD.codigo THEN
        RAISE EXCEPTION 'El codigo de un producto maestro no cambia (CCPIP-313).' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER producto_maestro_conservar_codigo BEFORE UPDATE OF codigo ON producto_maestro
    FOR EACH ROW EXECUTE FUNCTION producto_maestro_codigo_inmutable();

CREATE TRIGGER producto_maestro_fecha_desactivacion BEFORE UPDATE OF activo ON producto_maestro
    FOR EACH ROW EXECUTE FUNCTION catalogo_maestro_fecha_desactivacion();

CREATE TRIGGER producto_maestro_sin_delete BEFORE DELETE ON producto_maestro
    FOR EACH ROW EXECUTE FUNCTION catalogo_maestro_sin_borrado();

COMMENT ON COLUMN producto_maestro.desactivado_en IS 'CCPIP-314: desde cuando esta inactivo. Las ofertas que lo referencian no cambian.';

-- ---------------------------------------------------------------------------------------
-- Auditoria del catalogo maestro: solo insercion, igual que producto_auditoria
-- ---------------------------------------------------------------------------------------
CREATE TABLE catalogo_maestro_auditoria (
    id            bigserial    PRIMARY KEY,
    entidad       varchar(10)  NOT NULL,
    entidad_id    bigint       NOT NULL,
    usuario_id    bigint       NOT NULL REFERENCES usuario(id),
    accion        varchar(12)  NOT NULL,
    cambios       jsonb,
    registrado_en timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT catalogo_auditoria_entidad_valida CHECK (entidad IN ('LINEA', 'PRODUCTO')),
    CONSTRAINT catalogo_auditoria_accion_valida CHECK (accion IN ('CREAR', 'EDITAR', 'DESACTIVAR', 'REACTIVAR'))
);

CREATE INDEX catalogo_maestro_auditoria_entidad_idx ON catalogo_maestro_auditoria (entidad, entidad_id, registrado_en DESC);

COMMENT ON TABLE catalogo_maestro_auditoria IS 'Quien creo, cambio, desactivo o reactivo una linea o un producto maestro, cuando y que cambio (antes y despues).';

CREATE FUNCTION catalogo_maestro_auditoria_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'catalogo_maestro_auditoria es de solo insercion.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER catalogo_maestro_auditoria_sin_update BEFORE UPDATE ON catalogo_maestro_auditoria
    FOR EACH ROW EXECUTE FUNCTION catalogo_maestro_auditoria_solo_insercion();
CREATE TRIGGER catalogo_maestro_auditoria_sin_delete BEFORE DELETE ON catalogo_maestro_auditoria
    FOR EACH ROW EXECUTE FUNCTION catalogo_maestro_auditoria_solo_insercion();
