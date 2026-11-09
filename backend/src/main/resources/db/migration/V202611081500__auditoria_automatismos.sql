-- CCPIP-509: automatic effects and security state changes. Never persist tokens, hashes or recipients.
CREATE FUNCTION auditar_automatismos() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb := to_jsonb(NEW); o jsonb := CASE WHEN TG_OP IN ('UPDATE','DELETE') THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
    uid bigint; ubic bigint; referencia text; estado text; clave text; accion text; entidad text;
    fecha timestamptz:=now(); correlacion text; detalle jsonb:='{}'::jsonb; resultado text:='EXITOSA';
BEGIN
    CASE TG_TABLE_NAME
    WHEN 'pago' THEN
        IF TG_OP='UPDATE' AND NEW.estado IS NOT DISTINCT FROM OLD.estado THEN RETURN NEW; END IF;
        SELECT usuario_id, ubicacion_origen_id INTO uid,ubic FROM pedido WHERE id=NEW.pedido_id;
        referencia:=NEW.pedido_id::text; entidad:='PEDIDO'; accion:='PAGO'; resultado:=NEW.estado;
        fecha:=coalesce(NEW.anulado_en,NEW.resuelto_en,NEW.creado_en);
        clave:='PG-'||NEW.id||'-'||NEW.estado; correlacion:='PEDIDO-'||NEW.pedido_id;
        detalle:=jsonb_build_object('monto',NEW.monto,'pedidoId',NEW.pedido_id,'estadoNuevo',NEW.estado);
    WHEN 'aviso' THEN
        IF TG_OP='UPDATE' AND NEW.estado IS NOT DISTINCT FROM OLD.estado THEN RETURN NEW; END IF;
        referencia:=NEW.id::text; entidad:='AVISO'; accion:='AVISO_'||NEW.estado;
        clave:='AV-'||NEW.id||'-'||NEW.estado; correlacion:='AVISO-'||NEW.id;
        detalle:=jsonb_build_object('tipo',NEW.tipo,'estadoNuevo',NEW.estado,'referenciaId',NEW.referencia_id);
        resultado:=CASE NEW.estado WHEN 'FALLIDO' THEN 'ERROR' WHEN 'PENDIENTE' THEN 'PENDIENTE' ELSE 'EXITOSA' END;
    WHEN 'alerta' THEN
        IF TG_OP='UPDATE' AND NEW.correo IS NOT DISTINCT FROM OLD.correo AND NEW.resuelta_en IS NOT DISTINCT FROM OLD.resuelta_en THEN RETURN NEW; END IF;
        referencia:=NEW.id::text; entidad:='AVISO'; accion:='ALERTA_'||CASE WHEN NEW.resuelta_en IS NOT NULL THEN 'RESUELTA' ELSE NEW.correo END;
        clave:='AL-'||NEW.id||'-'||accion; ubic:=NEW.ubicacion_id; correlacion:='ALERTA-'||NEW.id;
        detalle:=jsonb_build_object('tipo',NEW.tipo,'estadoNuevo',NEW.correo,'referenciaId',NEW.referencia_id,'productoId',NEW.producto_id);
        resultado:=CASE NEW.correo WHEN 'FALLIDO' THEN 'ERROR' WHEN 'PENDIENTE' THEN 'PENDIENTE' ELSE 'EXITOSA' END;
    WHEN 'carrito' THEN
        IF NEW.vencido_en IS NULL OR NEW.vencido_en IS NOT DISTINCT FROM OLD.vencido_en THEN RETURN NEW; END IF;
        referencia:=NEW.id::text; entidad:='CARRITO'; accion:='CARRITO_VENCIDO';
        clave:='CV-'||NEW.id||'-'||extract(epoch FROM NEW.vencido_en)::text; fecha:=NEW.vencido_en;
        correlacion:='CARRITO-'||NEW.id; detalle:=jsonb_build_object('causa','VIGENCIA');
    WHEN 'usuario' THEN
        IF NEW.contrasena_hash IS DISTINCT FROM OLD.contrasena_hash THEN
            PERFORM registrar_evento_auditoria(NEW.id,NULL,'CONTRASENA_RESTABLECIDA','SEGURIDAD',NEW.id::text);
        END IF;
        IF NEW.bloqueado_hasta IS NOT NULL AND NEW.bloqueado_hasta IS DISTINCT FROM OLD.bloqueado_hasta THEN
            PERFORM registrar_evento_auditoria(NULL,NULL,'CUENTA_BLOQUEADA_INTENTOS','SEGURIDAD',NEW.id::text,
                now(),NULL,NULL,jsonb_build_object('cuentaId',NEW.id));
        END IF;
        RETURN NEW;
    WHEN 'sesion' THEN
        -- Invalidation is an automatic consequence; the affected account is not asserted to be the actor.
        PERFORM registrar_evento_auditoria(NULL,NULL,'SESION_INVALIDADA','SESION',OLD.usuario_id::text,
            now(),NULL,NULL,jsonb_build_object('cuentaId',OLD.usuario_id));
        RETURN OLD;
    ELSE RETURN NEW;
    END CASE;
    PERFORM registrar_evento_auditoria(uid,clave,accion,entidad,referencia,fecha,ubic,correlacion,detalle,resultado);
    RETURN NEW;
END;
$$;
CREATE TRIGGER auditar_pago AFTER INSERT OR UPDATE ON pago FOR EACH ROW EXECUTE FUNCTION auditar_automatismos();
CREATE TRIGGER auditar_aviso AFTER INSERT OR UPDATE ON aviso FOR EACH ROW EXECUTE FUNCTION auditar_automatismos();
CREATE TRIGGER auditar_alerta AFTER INSERT OR UPDATE ON alerta FOR EACH ROW EXECUTE FUNCTION auditar_automatismos();
CREATE TRIGGER auditar_carrito AFTER UPDATE ON carrito FOR EACH ROW EXECUTE FUNCTION auditar_automatismos();
CREATE TRIGGER auditar_seguridad_cuenta AFTER UPDATE ON usuario FOR EACH ROW EXECUTE FUNCTION auditar_automatismos();
CREATE TRIGGER auditar_invalidacion_sesion AFTER DELETE ON sesion FOR EACH ROW EXECUTE FUNCTION auditar_automatismos();
