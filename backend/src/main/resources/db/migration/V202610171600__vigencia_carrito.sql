-- CCPIP-142 (RF-109, RNF-36): el comprador retoma su carrito persistido durante un periodo de
-- vigencia configurable. El carrito no reserva existencias (RNF-35): vencer solo lo vacia, sin
-- movimientos de inventario.
--
-- La vigencia la fija el superadministrador en SA-02 "Parametros de la plataforma" (Figma 186:16653).
-- El valor esta "Pendiente de definicion del stakeholder" (CHANGELOG del Figma, 191:45), asi que aqui
-- no se siembra ninguno: mientras la Camara no lo configure rige el provisional de
-- ccpip.carrito.vigencia-provisional (application.properties).

CREATE TABLE parametro_plataforma (
    id             bigserial    PRIMARY KEY,
    clave          varchar(40)  NOT NULL,
    valor          integer      NOT NULL,
    registrado_por bigint       NOT NULL REFERENCES usuario(id),
    registrado_en  timestamptz  NOT NULL DEFAULT now(),
    -- Por ahora solo la vida del carrito; las resenas minimas y la anticipacion de alertas de SA-02
    -- entran con sus historias
    CONSTRAINT parametro_clave_valida CHECK (clave IN ('CARRITO_VIGENCIA_HORAS')),
    CONSTRAINT parametro_valor_positivo CHECK (valor > 0)
);

CREATE INDEX parametro_plataforma_clave ON parametro_plataforma (clave, registrado_en DESC, id DESC);

COMMENT ON TABLE parametro_plataforma IS
    'CCPIP-142: historial auditado de parametros de SA-02. Rige la ultima fila de cada clave.';
COMMENT ON COLUMN parametro_plataforma.valor IS
    'CARRITO_VIGENCIA_HORAS: horas desde el ultimo cambio del carrito durante las que se conserva.';

-- Es la auditoria del cambio, como comision_configuracion: no se edita ni se borra
CREATE OR REPLACE FUNCTION parametro_plataforma_inmutable()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'parametro_plataforma no se edita ni se borra: registra un valor nuevo (CCPIP-142).';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER parametro_plataforma_sin_cambios BEFORE UPDATE OR DELETE ON parametro_plataforma
    FOR EACH ROW EXECUTE FUNCTION parametro_plataforma_inmutable();

-- Cuando vencio y se vacio por ultima vez, para avisarle al comprador. Se limpia al volver a agregar.
ALTER TABLE carrito ADD COLUMN vencido_en timestamptz;

-- La tarea programada busca los carritos vencidos por actualizado_en
CREATE INDEX carrito_actualizado_en ON carrito (actualizado_en);

COMMENT ON TABLE carrito IS
    'Un carrito vigente por usuario. Vence actualizado_en + la vigencia de CCPIP-142, independiente de la sesion.';
COMMENT ON COLUMN carrito.actualizado_en IS
    'Ultimo cambio del comprador (agregar, modificar o quitar). Consultar no lo renueva.';
COMMENT ON COLUMN carrito.vencido_en IS
    'CCPIP-142: cuando se vacio por vencer. Nulo si nunca vencio o si ya se volvio a agregar algo.';
