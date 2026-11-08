-- CCPIP-509: capture new facts only. Existing history is read separately; no invented backfill.
CREATE FUNCTION registrar_evento_auditoria(
    p_usuario bigint, p_clave text, p_accion text, p_entidad text, p_referencia text,
    p_fecha timestamptz DEFAULT now(), p_ubicacion bigint DEFAULT NULL,
    p_correlacion text DEFAULT NULL, p_detalle jsonb DEFAULT '{}'::jsonb,
    p_resultado text DEFAULT 'EXITOSA'
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_nombre text; v_rol text;
BEGIN
    IF p_usuario IS NOT NULL THEN
        SELECT u.nombre, r.codigo INTO STRICT v_nombre, v_rol
        FROM usuario u JOIN rol r ON r.id=u.rol_id WHERE u.id=p_usuario;
    END IF;
    INSERT INTO auditoria_evento (usuario_id,usuario_nombre,rol,actor_tipo,clave_evento,accion,entidad,
        referencia,registrado_en,ubicacion_id,correlacion,detalle,resultado)
    VALUES (p_usuario,v_nombre,v_rol,CASE WHEN p_usuario IS NULL THEN 'SISTEMA' ELSE 'USUARIO' END,
        p_clave,p_accion,p_entidad,p_referencia,coalesce(p_fecha,now()),p_ubicacion,p_correlacion,
        jsonb_strip_nulls(p_detalle),p_resultado)
    ON CONFLICT (clave_evento) DO NOTHING;
END;
$$;

CREATE FUNCTION auditar_cambios_privados() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb := to_jsonb(NEW); o jsonb := CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
    uid bigint; accion text; entidad text; detalles jsonb := '{}'::jsonb; campos jsonb;
BEGIN
    IF TG_TABLE_NAME='cuenta_bancaria' THEN
        -- Compare business fields, excluding timestamps. Values never leave the source table.
        IF TG_OP='UPDATE' AND (n - ARRAY['actualizado_en','desactivada_en']) = (o - ARRAY['actualizado_en','desactivada_en']) THEN RETURN NEW; END IF;
        SELECT usuario_id INTO uid FROM productor WHERE id=NEW.productor_id;
        entidad:='CUENTA_BANCARIA';
        accion:=CASE WHEN TG_OP='INSERT' THEN 'CUENTA_BANCARIA_CREADA'
            WHEN NEW.estado IS DISTINCT FROM OLD.estado THEN CASE WHEN NEW.estado='ACTIVA' THEN 'CUENTA_BANCARIA_REACTIVADA' ELSE 'CUENTA_BANCARIA_DESACTIVADA' END
            ELSE 'CUENTA_BANCARIA_ACTUALIZADA' END;
        detalles:=jsonb_build_object('estadoNuevo',NEW.estado,'estadoAnterior',o->>'estado');
    ELSIF TG_TABLE_NAME='usuario' THEN
        IF (n->'nombres',n->'apellidos',n->'nombre',n->'telefono') IS NOT DISTINCT FROM
           (o->'nombres',o->'apellidos',o->'nombre',o->'telefono') THEN RETURN NEW; END IF;
        uid:=NEW.id;
        IF NOT EXISTS (SELECT 1 FROM rol WHERE id=NEW.rol_id AND codigo='CONSUMIDOR') THEN RETURN NEW; END IF;
        entidad:='CUENTA'; accion:='PERFIL_ACTUALIZADO';
    ELSIF TG_TABLE_NAME='direccion_guardada' THEN
        IF TG_OP='UPDATE' AND (n - ARRAY['actualizado_en']) = (o - ARRAY['actualizado_en']) THEN RETURN NEW; END IF;
        uid:=NEW.usuario_id; entidad:='DIRECCION';
        accion:=CASE WHEN TG_OP='INSERT' THEN 'DIRECCION_CREADA' WHEN NEW.activa IS DISTINCT FROM OLD.activa AND NOT NEW.activa THEN 'DIRECCION_RETIRADA' ELSE 'DIRECCION_ACTUALIZADA' END;
    ELSIF TG_TABLE_NAME='interes_compra' THEN
        IF TG_OP='UPDATE' AND (n->'cantidad',n->'motivo',n->'estado') IS NOT DISTINCT FROM (o->'cantidad',o->'motivo',o->'estado') THEN RETURN NEW; END IF;
        uid:=NEW.usuario_id; entidad:='INTERES';
        accion:=CASE WHEN TG_OP='INSERT' THEN 'INTERES_REGISTRADO' WHEN NEW.estado='RETIRADO' THEN 'INTERES_RETIRADO' WHEN NEW.estado='AVISADO' THEN 'INTERES_AVISADO' ELSE 'INTERES_ACTUALIZADO' END;
        IF NEW.estado='AVISADO' THEN uid:=NULL; END IF;
        detalles:=jsonb_build_object('cantidad',NEW.cantidad,'productoId',NEW.producto_id,'estadoNuevo',NEW.estado);
    ELSIF TG_TABLE_NAME='productor' THEN
        IF (n->'nombre_tienda',n->'descripcion_corta',n->'historia',n->'cita',n->'pueblo',n->'localidad',n->'socios',n->'tienda_publicada') IS NOT DISTINCT FROM
           (o->'nombre_tienda',o->'descripcion_corta',o->'historia',o->'cita',o->'pueblo',o->'localidad',o->'socios',o->'tienda_publicada') THEN RETURN NEW; END IF;
        uid:=NEW.usuario_id; entidad:='TIENDA';
        accion:=CASE WHEN NEW.tienda_publicada IS DISTINCT FROM OLD.tienda_publicada AND NEW.tienda_publicada THEN 'TIENDA_PUBLICADA' ELSE 'TIENDA_ACTUALIZADA' END;
    ELSE RETURN NEW;
    END IF;
    SELECT coalesce(jsonb_agg(k ORDER BY k),'[]'::jsonb) INTO campos
    FROM jsonb_object_keys(n) k WHERE n->k IS DISTINCT FROM o->k
        AND k <> ALL(ARRAY['actualizado_en','creado_en','desactivada_en','id']);
    detalles:=detalles || jsonb_build_object('campos',campos);
    PERFORM registrar_evento_auditoria(uid,NULL,accion,entidad,n->>'id',now(),NULL,NULL,detalles);
    RETURN NEW;
END;
$$;
CREATE TRIGGER auditar_cuenta_bancaria AFTER INSERT OR UPDATE ON cuenta_bancaria FOR EACH ROW EXECUTE FUNCTION auditar_cambios_privados();
CREATE TRIGGER auditar_perfil AFTER UPDATE ON usuario FOR EACH ROW EXECUTE FUNCTION auditar_cambios_privados();
CREATE TRIGGER auditar_direccion AFTER INSERT OR UPDATE ON direccion_guardada FOR EACH ROW EXECUTE FUNCTION auditar_cambios_privados();
CREATE TRIGGER auditar_interes AFTER INSERT OR UPDATE ON interes_compra FOR EACH ROW EXECUTE FUNCTION auditar_cambios_privados();
CREATE TRIGGER auditar_tienda AFTER UPDATE ON productor FOR EACH ROW EXECUTE FUNCTION auditar_cambios_privados();
CREATE INDEX auditoria_evento_fecha_idx ON auditoria_evento (registrado_en DESC,id DESC);
CREATE INDEX auditoria_evento_actor_fecha_idx ON auditoria_evento (actor_tipo,rol,registrado_en DESC);
CREATE INDEX auditoria_evento_referencia_idx ON auditoria_evento (entidad,referencia);
