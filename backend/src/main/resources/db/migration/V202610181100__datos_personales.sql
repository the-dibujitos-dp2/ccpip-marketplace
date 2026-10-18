-- CCPIP-400 (RF-292) y CCPIP-113 (RF-09), Figma CW-13 "Mis datos": el consumidor consulta y edita sus
-- datos personales y de contacto. Sin genero ni fecha de nacimiento.
--
-- El registro (CW-08) ya pide nombres y apellidos por separado, pero se guardaban juntos en "nombre".
-- Desde aqui se guardan tambien por separado; "nombre" sigue siendo el nombre completo que usa el resto
-- de la plataforma. En una cuenta anterior quedan nulos: Mis datos muestra el nombre completo como
-- nombres hasta que la persona lo corrija.
ALTER TABLE usuario ADD COLUMN nombres varchar(160);
ALTER TABLE usuario ADD COLUMN apellidos varchar(160);

COMMENT ON COLUMN usuario.nombres IS 'CCPIP-113: nombres; nulo en cuentas anteriores (rige nombre).';
COMMENT ON COLUMN usuario.apellidos IS 'CCPIP-113: apellidos; nulo en cuentas anteriores.';
