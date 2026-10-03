-- CCPIP-27: el inicio de sesion exige que el correo este confirmado.
-- Las cuentas nuevas nacen sin confirmar; las de demostracion ya sembradas se dan por confirmadas.

ALTER TABLE usuario ADD COLUMN correo_confirmado boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN usuario.correo_confirmado IS 'CCPIP-27: sin confirmar no se permite el acceso aunque la contrasena sea correcta.';

UPDATE usuario SET correo_confirmado = true WHERE correo LIKE '%@ccpip.test';
