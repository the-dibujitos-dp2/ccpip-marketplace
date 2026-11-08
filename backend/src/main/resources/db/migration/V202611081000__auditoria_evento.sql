CREATE TABLE auditoria_evento (
    id bigserial PRIMARY KEY,
    registrado_en timestamptz NOT NULL DEFAULT now(),
    usuario_id bigint,
    usuario_nombre varchar(160),
    rol varchar(40),
    actor_tipo varchar(10) NOT NULL CHECK (actor_tipo IN ('USUARIO','SISTEMA','ANONIMO')),
    accion varchar(100) NOT NULL,
    entidad varchar(100) NOT NULL,
    referencia varchar(255) NOT NULL,
    resultado varchar(40) NOT NULL,
    detalle jsonb NOT NULL DEFAULT '{}'::jsonb,
    ubicacion_id bigint,
    correlacion varchar(255),
    clave_evento varchar(255) UNIQUE
);

CREATE FUNCTION impedir_mutacion_auditoria_evento() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'auditoria_evento es append-only';
END;
$$;
CREATE TRIGGER auditoria_evento_append_only
BEFORE UPDATE OR DELETE ON auditoria_evento
FOR EACH ROW EXECUTE FUNCTION impedir_mutacion_auditoria_evento();

COMMENT ON TABLE auditoria_evento IS 'Bitacora append-only. detalle solo acepta estadoAnterior/estadoNuevo como codigo enum; campos seguros (ids, estado, tipo, codigo, fecha, cantidad, monto, porcentaje, valorAnterior, valorNuevo); IDs numericos y causa como codigo controlado. Nunca incluir texto libre, credenciales, tokens, cuentas ni PII.';
