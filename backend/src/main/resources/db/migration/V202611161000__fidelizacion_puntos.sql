-- CCPIP-86 (RF-92): el comprador acumula puntos al completarse la entrega de su pedido.
-- Regla base (decision del 13/11): 1 punto por cada S/ 10 de productos, sin envio, redondeado hacia abajo.
-- El sub administrador programa campanas por un periodo, que reemplazan esa regla mientras rigen.

CREATE TABLE campana_puntos (
    id              bigserial     PRIMARY KEY,
    nombre          varchar(80)   NOT NULL,
    descripcion     varchar(300),
    desde           date          NOT NULL,
    hasta           date          NOT NULL,
    puntos          integer       NOT NULL,
    cada_soles      numeric(8,2)  NOT NULL,
    monto_minimo    numeric(10,2) NOT NULL DEFAULT 0,
    tope_puntos     integer,
    registrado_por  bigint        NOT NULL REFERENCES usuario(id),
    registrado_en   timestamptz   NOT NULL DEFAULT now(),
    actualizado_por bigint        REFERENCES usuario(id),
    actualizado_en  timestamptz,
    finalizado_por  bigint        REFERENCES usuario(id),
    finalizado_en   timestamptz,
    CONSTRAINT campana_puntos_periodo CHECK (hasta >= desde),
    CONSTRAINT campana_puntos_regla CHECK (puntos BETWEEN 1 AND 1000 AND cada_soles >= 1),
    CONSTRAINT campana_puntos_minimo CHECK (monto_minimo >= 0),
    CONSTRAINT campana_puntos_tope CHECK (tope_puntos IS NULL OR tope_puntos > 0),
    CONSTRAINT campana_puntos_edicion CHECK ((actualizado_por IS NULL) = (actualizado_en IS NULL)),
    CONSTRAINT campana_puntos_fin CHECK ((finalizado_por IS NULL) = (finalizado_en IS NULL)),
    -- Dos campanas sin finalizar no se cruzan: un pedido nunca tiene dos reglas posibles
    CONSTRAINT campana_puntos_sin_cruce EXCLUDE USING gist (daterange(desde, hasta, '[]') WITH &&)
        WHERE (finalizado_en IS NULL)
);

COMMENT ON TABLE campana_puntos IS
    'CCPIP-86: campanas de puntos del sub administrador. Rige la que cubre la fecha de Lima en que se confirmo el pedido.';
COMMENT ON COLUMN campana_puntos.puntos IS 'Puntos que se ganan por cada cada_soles del pedido (productos, sin envio).';
COMMENT ON COLUMN campana_puntos.monto_minimo IS 'Pedido minimo (productos, sin envio) para la regla de la campana; debajo rige la base.';
COMMENT ON COLUMN campana_puntos.tope_puntos IS 'Maximo de puntos de un pedido con la campana; nulo = sin tope.';
COMMENT ON COLUMN campana_puntos.finalizado_en IS 'Fin anticipado: antes del inicio la campana queda cancelada; despues, rige hasta ese instante.';

CREATE TABLE movimiento_puntos (
    id               bigserial     PRIMARY KEY,
    usuario_id       bigint        NOT NULL REFERENCES usuario(id),
    pedido_id        bigint        NOT NULL REFERENCES pedido(id),
    tipo             varchar(20)   NOT NULL DEFAULT 'ACUMULACION',
    puntos           integer       NOT NULL,
    monto_base       numeric(12,2) NOT NULL,
    campana_id       bigint        REFERENCES campana_puntos(id),
    regla_puntos     integer       NOT NULL,
    regla_cada_soles numeric(8,2)  NOT NULL,
    registrado_en    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT movimiento_puntos_tipo CHECK (tipo IN ('ACUMULACION')),
    CONSTRAINT movimiento_puntos_positivos CHECK (puntos > 0),
    -- Un pedido acumula una sola vez, aunque la entrega se registre de nuevo
    CONSTRAINT movimiento_puntos_un_pedido UNIQUE (pedido_id, tipo)
);

CREATE INDEX movimiento_puntos_usuario_idx ON movimiento_puntos (usuario_id, registrado_en DESC);
CREATE INDEX movimiento_puntos_campana_idx ON movimiento_puntos (campana_id) WHERE campana_id IS NOT NULL;

COMMENT ON TABLE movimiento_puntos IS
    'CCPIP-86: puntos ganados por pedido entregado, con la regla que se aplico. Solo insercion: los puntos nunca se quitan.';

CREATE FUNCTION movimiento_puntos_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'movimiento_puntos no se edita ni se borra: los puntos ganados nunca se quitan (CCPIP-86).';
END $$;

CREATE TRIGGER movimiento_puntos_solo_insercion BEFORE UPDATE OR DELETE ON movimiento_puntos
    FOR EACH ROW EXECUTE FUNCTION movimiento_puntos_inmutable();

-- Auditoria (CCPIP-509): cada campana creada, editada o finalizada, sin textos libres
CREATE FUNCTION auditar_campana_puntos() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE uid bigint; clave text; accion text; fecha timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        uid := NEW.registrado_por; clave := 'CP-' || NEW.id; accion := 'CAMPANA_PUNTOS_CREADA'; fecha := NEW.registrado_en;
    ELSIF NEW.finalizado_en IS DISTINCT FROM OLD.finalizado_en THEN
        uid := NEW.finalizado_por; clave := 'CPF-' || NEW.id; accion := 'CAMPANA_PUNTOS_FINALIZADA'; fecha := NEW.finalizado_en;
    ELSIF NEW.actualizado_en IS DISTINCT FROM OLD.actualizado_en THEN
        uid := NEW.actualizado_por; accion := 'CAMPANA_PUNTOS_EDITADA'; fecha := NEW.actualizado_en;
        clave := 'CPE-' || NEW.id || '-' || floor(extract(epoch FROM NEW.actualizado_en) * 1000)::bigint;
    ELSE
        RETURN NEW;
    END IF;
    PERFORM registrar_evento_auditoria(uid, clave, accion, 'CAMPANA_PUNTOS', NEW.id::text, fecha, NULL, NULL,
        jsonb_build_object('fecha', NEW.desde::text, 'cantidad', NEW.puntos, 'monto', NEW.cada_soles));
    RETURN NEW;
END $$;

CREATE TRIGGER auditar_campana_puntos AFTER INSERT OR UPDATE ON campana_puntos
    FOR EACH ROW EXECUTE FUNCTION auditar_campana_puntos();
