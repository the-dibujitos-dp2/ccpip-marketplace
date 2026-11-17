-- CCPIP-88 (RF-94): beneficios por recurrencia. Niveles por pedidos entregados que multiplican los puntos.
-- CCPIP-89 (RF-95): el comprador canjea sus puntos por cupones de descuento del catalogo del sub administrador.
-- Codigos de descuento de las campanas. Un solo descuento por pedido; la Camara lo asume en su comision y los puntos
-- se ganan sobre lo pagado por los productos despues del descuento (decisiones del 17/11).

-- Niveles: el comprador sube de nivel por sus pedidos entregados; cada nivel multiplica los puntos que gana
CREATE TABLE nivel_fidelizacion (
    id              bigserial    PRIMARY KEY,
    nombre          varchar(40)  NOT NULL,
    pedidos_minimos integer      NOT NULL,
    multiplicador   numeric(3,2) NOT NULL,
    registrado_por  bigint       NOT NULL REFERENCES usuario(id),
    registrado_en   timestamptz  NOT NULL DEFAULT now(),
    actualizado_por bigint       REFERENCES usuario(id),
    actualizado_en  timestamptz,
    retirado_por    bigint       REFERENCES usuario(id),
    retirado_en     timestamptz,
    CONSTRAINT nivel_pedidos CHECK (pedidos_minimos BETWEEN 1 AND 10000),
    CONSTRAINT nivel_multiplicador CHECK (multiplicador > 1 AND multiplicador <= 5),
    CONSTRAINT nivel_edicion CHECK ((actualizado_por IS NULL) = (actualizado_en IS NULL)),
    CONSTRAINT nivel_retiro CHECK ((retirado_por IS NULL) = (retirado_en IS NULL))
);
CREATE UNIQUE INDEX nivel_nombre_vigente ON nivel_fidelizacion (lower(nombre)) WHERE retirado_en IS NULL;
CREATE UNIQUE INDEX nivel_pedidos_vigente ON nivel_fidelizacion (pedidos_minimos) WHERE retirado_en IS NULL;

COMMENT ON TABLE nivel_fidelizacion IS
    'CCPIP-88: niveles de recurrencia. El comprador esta en el de mayor pedidos_minimos que alcanza con sus entregas.';

-- Catalogo de cupones que se canjean con puntos
CREATE TABLE cupon (
    id              bigserial     PRIMARY KEY,
    nombre          varchar(80)   NOT NULL,
    descripcion     varchar(300),
    costo_puntos    integer       NOT NULL,
    tipo            varchar(12)   NOT NULL,
    valor           numeric(10,2) NOT NULL,
    monto_minimo    numeric(10,2) NOT NULL DEFAULT 0,
    tope_descuento  numeric(10,2),
    vigencia_dias   integer       NOT NULL,
    activo          boolean       NOT NULL DEFAULT true,
    registrado_por  bigint        NOT NULL REFERENCES usuario(id),
    registrado_en   timestamptz   NOT NULL DEFAULT now(),
    actualizado_por bigint        REFERENCES usuario(id),
    actualizado_en  timestamptz,
    CONSTRAINT cupon_costo CHECK (costo_puntos BETWEEN 1 AND 1000000),
    CONSTRAINT cupon_tipo CHECK (tipo IN ('MONTO', 'PORCENTAJE')),
    CONSTRAINT cupon_valor CHECK (valor > 0 AND (tipo = 'MONTO' OR valor <= 90)),
    CONSTRAINT cupon_minimo CHECK (monto_minimo >= 0),
    CONSTRAINT cupon_tope CHECK (tope_descuento IS NULL OR (tipo = 'PORCENTAJE' AND tope_descuento > 0)),
    CONSTRAINT cupon_vigencia CHECK (vigencia_dias BETWEEN 1 AND 365),
    CONSTRAINT cupon_edicion CHECK ((actualizado_por IS NULL) = (actualizado_en IS NULL))
);

-- Un cupon canjeado por un comprador: copia el beneficio del catalogo, que despues puede cambiar
CREATE TABLE cupon_cliente (
    id             bigserial     PRIMARY KEY,
    cupon_id       bigint        NOT NULL REFERENCES cupon(id),
    usuario_id     bigint        NOT NULL REFERENCES usuario(id),
    codigo         varchar(20)   NOT NULL UNIQUE,
    nombre         varchar(80)   NOT NULL,
    tipo           varchar(12)   NOT NULL,
    valor          numeric(10,2) NOT NULL,
    monto_minimo   numeric(10,2) NOT NULL,
    tope_descuento numeric(10,2),
    costo_puntos   integer       NOT NULL,
    canjeado_en    timestamptz   NOT NULL DEFAULT now(),
    vence_en       timestamptz   NOT NULL,
    CONSTRAINT cupon_cliente_vence CHECK (vence_en > canjeado_en)
);
CREATE INDEX cupon_cliente_usuario_idx ON cupon_cliente (usuario_id, canjeado_en DESC);

-- Codigos de descuento de una campana: rigen mientras rige la campana
CREATE TABLE codigo_descuento (
    id               bigserial     PRIMARY KEY,
    campana_id       bigint        NOT NULL REFERENCES campana_puntos(id),
    codigo           varchar(20)   NOT NULL,
    tipo             varchar(12)   NOT NULL,
    valor            numeric(10,2) NOT NULL,
    monto_minimo     numeric(10,2) NOT NULL DEFAULT 0,
    tope_descuento   numeric(10,2),
    usos_maximos     integer,
    usos_por_cliente integer       NOT NULL DEFAULT 1,
    registrado_por   bigint        NOT NULL REFERENCES usuario(id),
    registrado_en    timestamptz   NOT NULL DEFAULT now(),
    desactivado_por  bigint        REFERENCES usuario(id),
    desactivado_en   timestamptz,
    CONSTRAINT codigo_formato CHECK (codigo ~ '^[A-Z0-9]{4,20}$'),
    CONSTRAINT codigo_tipo CHECK (tipo IN ('MONTO', 'PORCENTAJE')),
    CONSTRAINT codigo_valor CHECK (valor > 0 AND (tipo = 'MONTO' OR valor <= 90)),
    CONSTRAINT codigo_minimo CHECK (monto_minimo >= 0),
    CONSTRAINT codigo_tope CHECK (tope_descuento IS NULL OR (tipo = 'PORCENTAJE' AND tope_descuento > 0)),
    CONSTRAINT codigo_usos CHECK ((usos_maximos IS NULL OR usos_maximos >= 1) AND usos_por_cliente BETWEEN 1 AND 100),
    CONSTRAINT codigo_desactivado CHECK ((desactivado_por IS NULL) = (desactivado_en IS NULL))
);
CREATE UNIQUE INDEX codigo_descuento_unico ON codigo_descuento (codigo);

-- El pedido guarda su descuento: uno solo, de un cupon canjeado o de un codigo de campana
ALTER TABLE pedido ADD COLUMN descuento_monto     numeric(12,2) NOT NULL DEFAULT 0;
ALTER TABLE pedido ADD COLUMN cupon_cliente_id    bigint REFERENCES cupon_cliente(id);
ALTER TABLE pedido ADD COLUMN codigo_descuento_id bigint REFERENCES codigo_descuento(id);
ALTER TABLE pedido ADD CONSTRAINT pedido_descuento_valido CHECK (descuento_monto = 0 OR (descuento_monto > 0 AND descuento_monto < total));
ALTER TABLE pedido ADD CONSTRAINT pedido_un_descuento CHECK (cupon_cliente_id IS NULL OR codigo_descuento_id IS NULL);
ALTER TABLE pedido ADD CONSTRAINT pedido_descuento_con_origen
    CHECK (descuento_monto = 0 OR cupon_cliente_id IS NOT NULL OR codigo_descuento_id IS NOT NULL);
-- Un cupon se usa en un solo pedido vigente; si ese pedido se cancela, vuelve a estar disponible
CREATE UNIQUE INDEX pedido_cupon_unico ON pedido (cupon_cliente_id) WHERE cupon_cliente_id IS NOT NULL AND estado <> 'CANCELADO';
CREATE INDEX pedido_codigo_idx ON pedido (codigo_descuento_id) WHERE codigo_descuento_id IS NOT NULL;

COMMENT ON COLUMN pedido.descuento_monto IS
    'CCPIP-88/89: descuento sobre los productos. Lo asume la Camara: se cobra total + costo_envio - descuento_monto.';

-- Movimientos de puntos: ademas de lo acumulado, lo canjeado por cupones. Los puntos ganados nunca se quitan; un
-- canje es un movimiento aparte. Cada acumulacion guarda el nivel y el multiplicador que se aplicaron.
ALTER TABLE movimiento_puntos DROP CONSTRAINT movimiento_puntos_tipo;
ALTER TABLE movimiento_puntos ADD CONSTRAINT movimiento_puntos_tipo CHECK (tipo IN ('ACUMULACION', 'CANJE'));
ALTER TABLE movimiento_puntos ALTER COLUMN pedido_id DROP NOT NULL;
ALTER TABLE movimiento_puntos ALTER COLUMN monto_base DROP NOT NULL;
ALTER TABLE movimiento_puntos ALTER COLUMN regla_puntos DROP NOT NULL;
ALTER TABLE movimiento_puntos ALTER COLUMN regla_cada_soles DROP NOT NULL;
ALTER TABLE movimiento_puntos ADD COLUMN cupon_cliente_id bigint UNIQUE REFERENCES cupon_cliente(id);
ALTER TABLE movimiento_puntos ADD COLUMN nivel varchar(40);
ALTER TABLE movimiento_puntos ADD COLUMN multiplicador numeric(3,2) NOT NULL DEFAULT 1;
ALTER TABLE movimiento_puntos ADD CONSTRAINT movimiento_puntos_origen CHECK (
    (tipo = 'ACUMULACION' AND pedido_id IS NOT NULL AND cupon_cliente_id IS NULL AND monto_base IS NOT NULL
        AND regla_puntos IS NOT NULL AND regla_cada_soles IS NOT NULL)
    OR (tipo = 'CANJE' AND cupon_cliente_id IS NOT NULL AND pedido_id IS NULL));

COMMENT ON COLUMN movimiento_puntos.puntos IS 'Siempre positivo: ACUMULACION suma al saldo y CANJE resta.';

-- Auditoria (CCPIP-509): cambios del sub administrador en cupones, codigos y niveles, sin textos libres
CREATE FUNCTION auditar_beneficios() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE uid bigint; clave text; accion text; entidad text; fecha timestamptz;
BEGIN
    IF TG_TABLE_NAME = 'cupon' THEN
        entidad := 'CUPON';
        IF TG_OP = 'INSERT' THEN
            uid := NEW.registrado_por; clave := 'CU-' || NEW.id; accion := 'CUPON_CREADO'; fecha := NEW.registrado_en;
        ELSIF NEW.actualizado_en IS DISTINCT FROM OLD.actualizado_en THEN
            uid := NEW.actualizado_por; fecha := NEW.actualizado_en;
            accion := CASE WHEN NEW.activo IS DISTINCT FROM OLD.activo
                           THEN CASE WHEN NEW.activo THEN 'CUPON_ACTIVADO' ELSE 'CUPON_DESACTIVADO' END
                           ELSE 'CUPON_EDITADO' END;
            clave := 'CUE-' || NEW.id || '-' || floor(extract(epoch FROM NEW.actualizado_en) * 1000)::bigint;
        ELSE
            RETURN NEW;
        END IF;
    ELSIF TG_TABLE_NAME = 'codigo_descuento' THEN
        entidad := 'CODIGO_DESCUENTO';
        IF TG_OP = 'INSERT' THEN
            uid := NEW.registrado_por; clave := 'CD-' || NEW.id; accion := 'CODIGO_CREADO'; fecha := NEW.registrado_en;
        ELSIF NEW.desactivado_en IS DISTINCT FROM OLD.desactivado_en THEN
            uid := NEW.desactivado_por; clave := 'CDX-' || NEW.id; accion := 'CODIGO_DESACTIVADO'; fecha := NEW.desactivado_en;
        ELSE
            RETURN NEW;
        END IF;
    ELSE
        entidad := 'NIVEL_FIDELIZACION';
        IF TG_OP = 'INSERT' THEN
            uid := NEW.registrado_por; clave := 'NF-' || NEW.id; accion := 'NIVEL_CREADO'; fecha := NEW.registrado_en;
        ELSIF NEW.retirado_en IS DISTINCT FROM OLD.retirado_en THEN
            uid := NEW.retirado_por; clave := 'NFX-' || NEW.id; accion := 'NIVEL_RETIRADO'; fecha := NEW.retirado_en;
        ELSIF NEW.actualizado_en IS DISTINCT FROM OLD.actualizado_en THEN
            uid := NEW.actualizado_por; accion := 'NIVEL_EDITADO'; fecha := NEW.actualizado_en;
            clave := 'NFE-' || NEW.id || '-' || floor(extract(epoch FROM NEW.actualizado_en) * 1000)::bigint;
        ELSE
            RETURN NEW;
        END IF;
    END IF;
    PERFORM registrar_evento_auditoria(uid, clave, accion, entidad, NEW.id::text, fecha, NULL, NULL, '{}'::jsonb);
    RETURN NEW;
END $$;

CREATE TRIGGER auditar_cupon AFTER INSERT OR UPDATE ON cupon FOR EACH ROW EXECUTE FUNCTION auditar_beneficios();
CREATE TRIGGER auditar_codigo_descuento AFTER INSERT OR UPDATE ON codigo_descuento FOR EACH ROW EXECUTE FUNCTION auditar_beneficios();
CREATE TRIGGER auditar_nivel_fidelizacion AFTER INSERT OR UPDATE ON nivel_fidelizacion FOR EACH ROW EXECUTE FUNCTION auditar_beneficios();

-- El pago de un pedido con descuento es lo que se cobra: productos y envio menos el descuento (CCPIP-88/89)
CREATE OR REPLACE FUNCTION pago_al_crear()
RETURNS trigger AS $$
DECLARE
    pedido_estado varchar(30);
    pedido_monto  numeric(12,2);
BEGIN
    SELECT estado, total + costo_envio - descuento_monto INTO pedido_estado, pedido_monto FROM pedido WHERE id = NEW.pedido_id;
    IF pedido_estado IS DISTINCT FROM 'PENDIENTE_PAGO' THEN
        RAISE EXCEPTION 'Solo se registra un pago para un pedido en PENDIENTE_PAGO (este esta en %).', pedido_estado;
    END IF;
    IF NEW.monto <> pedido_monto THEN
        RAISE EXCEPTION 'El monto del pago (%) debe ser igual al total del pedido con envio y descuento (%).', NEW.monto, pedido_monto;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
