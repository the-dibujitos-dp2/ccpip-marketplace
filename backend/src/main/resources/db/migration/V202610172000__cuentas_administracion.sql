-- Cuentas de la administracion (SA-03, SA-04): CCPIP-124, 125, 301 a 308 y CCPIP-95.
-- El superadministrador crea, modifica y desactiva cuentas de sub administrador y de administrador
-- de punto de acopio; un administrador autorizado suspende y reactiva cuentas con motivo.
-- Ninguna cuenta se borra (RNF-18): cambia de estado y su historial se conserva.

-- Estado de la cuenta. activo se conserva porque el inicio de sesion y las sesiones lo leen; la
-- restriccion los mantiene coherentes: solo una cuenta ACTIVA puede ingresar.
ALTER TABLE usuario ADD COLUMN estado varchar(20) NOT NULL DEFAULT 'ACTIVA';
UPDATE usuario SET estado = 'DESACTIVADA' WHERE NOT activo;
ALTER TABLE usuario ADD CONSTRAINT usuario_estado_valido CHECK (estado IN ('ACTIVA', 'SUSPENDIDA', 'DESACTIVADA'));
ALTER TABLE usuario ADD CONSTRAINT usuario_estado_coherente CHECK (activo = (estado = 'ACTIVA'));

COMMENT ON COLUMN usuario.estado IS 'CCPIP-303, 306, 307: ACTIVA, SUSPENDIDA (se puede reactivar) o DESACTIVADA (definitiva). Nunca se borra la fila.';

-- Historial de cada cuenta: quien hizo que, cuando y por que. Solo insercion.
CREATE TABLE cuenta_historial (
    id              bigserial    PRIMARY KEY,
    usuario_id      bigint       NOT NULL REFERENCES usuario(id),
    accion          varchar(20)  NOT NULL,
    motivo          varchar(300),
    cambios         jsonb,
    realizado_por   bigint       NOT NULL REFERENCES usuario(id),
    realizado_en    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT cuenta_historial_accion_valida CHECK (accion IN
        ('CREAR', 'MODIFICAR', 'SUSPENDER', 'REACTIVAR', 'DESACTIVAR', 'REENVIAR_ACCESO')),
    -- Suspender, reactivar y desactivar siempre llevan motivo (CCPIP-303, 306, 307, 308)
    CONSTRAINT cuenta_historial_motivo_requerido CHECK (
        accion NOT IN ('SUSPENDER', 'REACTIVAR', 'DESACTIVAR') OR length(btrim(motivo)) > 0)
);

CREATE INDEX cuenta_historial_usuario_idx ON cuenta_historial (usuario_id, realizado_en DESC);

COMMENT ON TABLE cuenta_historial IS 'CCPIP-124, 125, 301 a 308: auditoria de las cuentas. No se edita ni se borra.';
COMMENT ON COLUMN cuenta_historial.cambios IS 'Datos antes y despues (nombre, telefono, puntos). Nunca contrasenas ni tokens.';

CREATE OR REPLACE FUNCTION cuenta_historial_inmutable()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'cuenta_historial no se edita ni se borra (CCPIP-303).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cuenta_historial_sin_update BEFORE UPDATE ON cuenta_historial
    FOR EACH ROW EXECUTE FUNCTION cuenta_historial_inmutable();
CREATE TRIGGER cuenta_historial_sin_delete BEFORE DELETE ON cuenta_historial
    FOR EACH ROW EXECUTE FUNCTION cuenta_historial_inmutable();

-- Para buscar cuentas por nombre (CCPIP-95); el correo ya tiene usuario_correo_lower_idx.
CREATE INDEX usuario_nombre_lower_idx ON usuario (lower(nombre));
