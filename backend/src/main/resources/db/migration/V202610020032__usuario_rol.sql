-- V1: identidad y acceso
-- Cubre CCPIP-27 (iniciar y cerrar sesion) y CCPIP-151 (verificar rol en el servidor).
-- No hay tabla de registro ni de postulacion: las cuentas entran sembradas (CCPIP-150).

CREATE TABLE rol (
    id              smallint     PRIMARY KEY,
    codigo          varchar(40)  NOT NULL UNIQUE,
    nombre          varchar(80)  NOT NULL
);

COMMENT ON TABLE rol IS 'Catalogo cerrado de los seis roles de la plataforma. No se crea ni se edita desde la aplicacion en Sprint 0 (eso es CCPIP-28).';

CREATE TABLE usuario (
    id              bigserial    PRIMARY KEY,
    correo          varchar(255) NOT NULL,
    contrasena_hash varchar(255) NOT NULL,
    nombre          varchar(160) NOT NULL,
    rol_id          smallint     NOT NULL REFERENCES rol(id),
    activo          boolean      NOT NULL DEFAULT true,
    intentos_fallidos smallint   NOT NULL DEFAULT 0,
    bloqueado_hasta timestamptz,
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT usuario_correo_unico UNIQUE (correo),
    CONSTRAINT usuario_intentos_no_negativos CHECK (intentos_fallidos >= 0)
);

-- RNF-40: el correo es unico en toda la plataforma.
-- Se indexa en minusculas para que la unicidad no dependa de como lo escribio el usuario.
CREATE UNIQUE INDEX usuario_correo_lower_idx ON usuario (lower(correo));

COMMENT ON COLUMN usuario.contrasena_hash IS 'RNF-01: solo el hash. La aplicacion nunca guarda la contrasena en claro, ni cifrada reversible.';
COMMENT ON COLUMN usuario.bloqueado_hasta IS 'RNF-02: bloqueo temporal tras varios intentos fallidos. Nulo significa no bloqueado.';

-- ADR-05: sesion con token opaco aleatorio en cookie HttpOnly, guardada en el servidor.
-- Cerrar sesion borra la fila, lo que invalida el acceso de inmediato (RNF-03).
-- Se guarda el hash SHA-256 del token, no el token: quien lea la base no puede
-- reutilizar una sesion.
CREATE TABLE sesion (
    token_hash      char(64)     PRIMARY KEY,
    usuario_id      bigint       NOT NULL REFERENCES usuario(id),
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    ultimo_acceso   timestamptz  NOT NULL DEFAULT now()
);

-- Para cerrar todas las sesiones de un usuario al bloquearlo o desactivarlo.
CREATE INDEX sesion_usuario_idx ON sesion (usuario_id);
-- Para purgar las sesiones vencidas por inactividad.
CREATE INDEX sesion_ultimo_acceso_idx ON sesion (ultimo_acceso);

COMMENT ON TABLE sesion IS 'RNF-03: una fila por sesion activa. Expira por inactividad segun ultimo_acceso y se borra al cerrar sesion.';
COMMENT ON COLUMN sesion.token_hash IS 'SHA-256 en hexadecimal del token entregado en la cookie. El token en claro nunca se guarda.';
