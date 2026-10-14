-- CCPIP-26: registro del consumidor. CCPIP-328: verificacion del correo con enlace de un solo uso.
-- Sigue el diccionario de datos (token_un_uso y usuario.telefono).

-- Celular opcional que la persona da al registrarse (pantalla CW-08).
ALTER TABLE usuario ADD COLUMN telefono varchar(20);

COMMENT ON COLUMN usuario.telefono IS 'CCPIP-26: celular de contacto, opcional.';

-- Enlaces de un solo uso: verificar el correo y, mas adelante, recuperar la clave (CCPIP-33).
-- Igual que en sesion, solo se guarda el SHA-256 del token: quien lea la base no puede usar el enlace.
CREATE TABLE token_un_uso (
    id          bigserial   PRIMARY KEY,
    usuario_id  bigint      NOT NULL REFERENCES usuario(id),
    proposito   varchar(30) NOT NULL,
    token_hash  char(64)    NOT NULL,
    creado_en   timestamptz NOT NULL DEFAULT now(),
    expira_en   timestamptz NOT NULL,
    usado_en    timestamptz,
    CONSTRAINT token_un_uso_hash_unico UNIQUE (token_hash),
    CONSTRAINT token_un_uso_proposito_valido CHECK (proposito IN ('VERIFICAR_CORREO', 'RECUPERAR_CLAVE')),
    CONSTRAINT token_un_uso_expira_despues CHECK (expira_en > creado_en),
    CONSTRAINT token_un_uso_usado_despues CHECK (usado_en IS NULL OR usado_en >= creado_en)
);

-- Para reenviar: buscar los enlaces vigentes de una cuenta y proposito.
CREATE INDEX token_un_uso_usuario_idx ON token_un_uso (usuario_id, proposito);

COMMENT ON TABLE token_un_uso IS 'CCPIP-328: enlaces de un solo uso. Un enlace usado o vencido ya no sirve.';
COMMENT ON COLUMN token_un_uso.token_hash IS 'SHA-256 en hexadecimal del token del enlace; el token en claro no se guarda.';
