-- CCPIP-296 (RF-172): el superadministrador modifica los permisos configurables de cada rol, respetando
-- las restricciones de gobernanza. Cada permiso configurable agrupa acciones que el servidor ya protege
-- por rol (@RolesPermitidos); revocarlo quita esas acciones al rol aunque su rol las tenga declaradas.
-- Un rol sin fila para un permiso lo conserva tal como lo declara el servidor (CCPIP-28).
--
-- Restricciones de gobernanza: la Camara nunca obtiene permisos de edicion sobre productos, tiendas,
-- pedidos, datos personales de compradores o existencias. Esos permisos figuran como restringidos y la
-- base impide concederlos. Todo cambio queda en permiso_configurable_cambio, de solo insercion.

CREATE TABLE permiso_configurable (
    rol_id          smallint     NOT NULL REFERENCES rol(id),
    codigo          varchar(40)  NOT NULL,
    restringido     boolean      NOT NULL DEFAULT false,
    concedido       boolean      NOT NULL,
    actualizado_en  timestamptz,
    actualizado_por bigint       REFERENCES usuario(id),
    PRIMARY KEY (rol_id, codigo),
    CONSTRAINT permiso_restringido_nunca_concedido CHECK (NOT (restringido AND concedido)),
    CONSTRAINT permiso_actualizado_coherente CHECK ((actualizado_en IS NULL) = (actualizado_por IS NULL))
);

COMMENT ON TABLE permiso_configurable IS 'CCPIP-296: permisos que el superadministrador concede o revoca a cada rol. Sin fila, el rol conserva lo que declara el servidor.';
COMMENT ON COLUMN permiso_configurable.restringido IS 'Restriccion de gobernanza (RF-172): nunca se concede. Lo impide la base, no solo la aplicacion.';

CREATE TABLE permiso_configurable_cambio (
    id           bigserial    PRIMARY KEY,
    rol_id       smallint     NOT NULL,
    codigo       varchar(40)  NOT NULL,
    concedido    boolean      NOT NULL,
    motivo       varchar(300) NOT NULL,
    realizado_por bigint      NOT NULL REFERENCES usuario(id),
    realizado_en timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (rol_id, codigo) REFERENCES permiso_configurable (rol_id, codigo),
    CONSTRAINT permiso_cambio_motivo_presente CHECK (btrim(motivo) <> '')
);

CREATE INDEX permiso_configurable_cambio_fecha_idx ON permiso_configurable_cambio (realizado_en DESC);

COMMENT ON TABLE permiso_configurable_cambio IS 'CCPIP-296: quien concedio o revoco cada permiso, cuando y por que. Solo insercion; aparece en la auditoria (CCPIP-97).';

-- Un cambio auditado no se reescribe ni se borra
CREATE FUNCTION permiso_cambio_solo_insercion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'permiso_configurable_cambio es de solo insercion (CCPIP-296).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER permiso_cambio_sin_update BEFORE UPDATE ON permiso_configurable_cambio
    FOR EACH ROW EXECUTE FUNCTION permiso_cambio_solo_insercion();
CREATE TRIGGER permiso_cambio_sin_delete BEFORE DELETE ON permiso_configurable_cambio
    FOR EACH ROW EXECUTE FUNCTION permiso_cambio_solo_insercion();

-- Un permiso no se borra ni cambia de rol, codigo o restriccion: solo se concede o se revoca
CREATE FUNCTION permiso_configurable_al_cambiar() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Un permiso configurable no se borra (CCPIP-296): se revoca.';
    END IF;
    IF NEW.rol_id <> OLD.rol_id OR NEW.codigo <> OLD.codigo OR NEW.restringido <> OLD.restringido THEN
        RAISE EXCEPTION 'Un permiso configurable no cambia de rol, codigo ni restriccion.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER permiso_configurable_sin_reescribir BEFORE UPDATE OR DELETE ON permiso_configurable
    FOR EACH ROW EXECUTE FUNCTION permiso_configurable_al_cambiar();

-- Las filas iniciales van en R__07_permisos_configurables.sql: los roles los carga R__01, que Flyway corre
-- despues de las migraciones versionadas.
