-- CCPIP-265 (RF-135): el usuario elige espanol o ingles y la plataforma conserva su preferencia.
-- El visitante la guarda en su navegador; con sesion tambien queda en su cuenta, para que lo siga
-- en otro dispositivo. Las cuentas existentes y las nuevas nacen en espanol.

ALTER TABLE usuario ADD COLUMN idioma varchar(2) NOT NULL DEFAULT 'es';

ALTER TABLE usuario ADD CONSTRAINT usuario_idioma_valido CHECK (idioma IN ('es', 'en'));

COMMENT ON COLUMN usuario.idioma IS 'CCPIP-265: idioma preferido de la interfaz y del contenido, es o en (RNF-44).';
