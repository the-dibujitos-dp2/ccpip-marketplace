-- CCPIP-509: snapshots nuevos únicamente; sin backfill. Los eventos guardan códigos/IDs y
-- campos seguros. No se copian motivos, textos libres, historias ni descripciones.
CREATE FUNCTION auditar_gobernanza_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb := to_jsonb(NEW); o jsonb := CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
 uid bigint; ref text; clave text; accion text; entidad text; detalle jsonb := '{}'::jsonb;
BEGIN
 IF TG_OP='UPDATE' AND (n - ARRAY['registrado_en','realizado_en','decidido_en','actualizado_en']) = (o - ARRAY['registrado_en','realizado_en','decidido_en','actualizado_en']) THEN RETURN NEW; END IF;
 CASE TG_TABLE_NAME
 WHEN 'producto_auditoria' THEN
  uid:=NEW.usuario_id; ref:=NEW.producto_id::text; clave:='PA-'||NEW.id; accion:='PRODUCTO_'||NEW.accion; entidad:='PRODUCTO';
  detalle:=jsonb_build_object('productoId',NEW.producto_id,'campos',coalesce((SELECT jsonb_agg(k) FROM jsonb_object_keys(coalesce(NEW.cambios,'{}')) k WHERE k IN ('estado','lineaId','unidadMedidaId','precio','publicadoEn')), '[]'::jsonb));
 WHEN 'catalogo_maestro_auditoria' THEN
  uid:=NEW.usuario_id; ref:=NEW.entidad_id::text; clave:='CM-'||NEW.id; accion:='CATALOGO_'||NEW.accion; entidad:=CASE NEW.entidad WHEN 'LINEA' THEN 'LINEA_MAESTRA' WHEN 'PRODUCTO' THEN 'PRODUCTO_MAESTRO' ELSE 'UNIDAD' END;
  detalle:=jsonb_build_object('codigo',NEW.cambios->>'codigo');
 WHEN 'cuenta_historial' THEN uid:=NEW.realizado_por; ref:=NEW.usuario_id::text; clave:='CH-'||NEW.id; accion:='CUENTA_'||NEW.accion; entidad:='CUENTA'; detalle:=jsonb_build_object('campos',coalesce(NEW.cambios->'campos','[]'::jsonb));
 WHEN 'autorizacion_decision' THEN uid:=NEW.decidido_por; ref:=NEW.autorizacion_id::text; clave:='AD-'||NEW.id; accion:='AUTORIZACION_'||NEW.decision; entidad:='AUTORIZACION'; detalle:=jsonb_build_object('decision',NEW.decision);
 WHEN 'productor_decision' THEN uid:=NEW.decidido_por; ref:=NEW.productor_id::text; clave:='PD-'||NEW.id; accion:=NEW.decision; entidad:='PRODUCTOR'; detalle:=jsonb_build_object('decision',NEW.decision);
 WHEN 'moderacion_publicacion' THEN
  IF NEW.decision IS NULL OR (TG_OP='UPDATE' AND OLD.decision IS NOT NULL) THEN RETURN NEW; END IF;
  uid:=NEW.decidida_por; ref:=NEW.id::text; clave:='MP-'||NEW.id; accion:='PUBLICACION_'||NEW.decision; entidad:='MODERACION'; detalle:=jsonb_build_object('decision',NEW.decision,'productoId',NEW.producto_id);
 WHEN 'renovacion_certificado' THEN uid:=NEW.solicitada_por; ref:=NEW.autorizacion_id::text; clave:='RC-'||NEW.id; accion:='RENOVACION_SOLICITADA'; entidad:='AUTORIZACION'; detalle:='{}'::jsonb;
 WHEN 'comision_configuracion' THEN
  IF TG_OP='UPDATE' AND NEW.cancelado_en IS NOT DISTINCT FROM OLD.cancelado_en THEN RETURN NEW; END IF;
  IF NEW.cancelado_en IS NULL THEN uid:=NEW.registrado_por; clave:='CC-'||NEW.id; accion:='COMISION_PROGRAMADA'; detalle:=jsonb_build_object('porcentaje',NEW.porcentaje);
  ELSE uid:=NEW.cancelado_por; clave:='CCX-'||NEW.id; accion:='COMISION_CANCELADA'; detalle:='{}'::jsonb; END IF;
  ref:=NEW.id::text; entidad:='COMISION';
 WHEN 'parametro_plataforma' THEN uid:=NEW.registrado_por; ref:=NEW.id::text; clave:='PP-'||NEW.id; accion:='PARAMETRO_CAMBIADO'; entidad:='PARAMETRO'; detalle:=jsonb_build_object('codigo',NEW.clave,'valor',NEW.valor);
 WHEN 'motivo_merma_cambio' THEN uid:=NEW.registrado_por; ref:=NEW.motivo_id::text; clave:='MM-'||NEW.id; accion:='MOTIVO_MERMA_'||NEW.accion; entidad:='MOTIVO_MERMA'; detalle:=jsonb_build_object('cambio',NEW.accion);
 WHEN 'permiso_configurable_cambio' THEN uid:=NEW.realizado_por; ref:=NEW.rol_id::text||':'||NEW.codigo; clave:='PC-'||NEW.id; accion:=CASE WHEN NEW.concedido THEN 'PERMISO_CONCEDIDO' ELSE 'PERMISO_REVOCADO' END; entidad:='PERMISO'; detalle:=jsonb_build_object('codigo',NEW.codigo,'concedido',NEW.concedido);
 WHEN 'tarifa_envio_cambio' THEN uid:=NEW.realizado_por; ref:=NEW.ubigeo::text; clave:='TE-'||NEW.id; accion:='TARIFA_ENVIO'; entidad:='TARIFA_ENVIO'; detalle:=jsonb_build_object('montoAnterior',NEW.monto_anterior,'montoNuevo',NEW.monto_nuevo);
 WHEN 'capacidad_entrega_cambio' THEN uid:=NEW.realizado_por; ref:=NEW.ubicacion_id::text; clave:='CE-'||NEW.id; accion:='CAPACIDAD_ENTREGA'; entidad:='CAPACIDAD_ENTREGA'; detalle:=jsonb_build_object('capacidadAnterior',NEW.maximo_anterior,'capacidadNueva',NEW.maximo_nuevo);
 WHEN 'cobertura_entrega_cambio' THEN uid:=NEW.realizado_por; ref:=NEW.cobertura_id::text; clave:='CZ-'||NEW.id; accion:=CASE WHEN NEW.activa THEN 'COBERTURA_ACTIVADA' ELSE 'COBERTURA_DESACTIVADA' END; entidad:='COBERTURA_ENTREGA'; detalle:=jsonb_build_object('activa',NEW.activa);
 ELSE RETURN NEW;
 END CASE;
 PERFORM registrar_evento_auditoria(uid,clave,accion,entidad,ref,coalesce((n->>'registrado_en')::timestamptz,(n->>'realizado_en')::timestamptz,(n->>'decidido_en')::timestamptz,now()),NULL,NULL,detalle);
 RETURN NEW;
END $$;

CREATE TRIGGER auditar_gobernanza_producto AFTER INSERT ON producto_auditoria FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_catalogo AFTER INSERT ON catalogo_maestro_auditoria FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_cuenta AFTER INSERT ON cuenta_historial FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_autorizacion AFTER INSERT ON autorizacion_decision FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_productor AFTER INSERT ON productor_decision FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_moderacion AFTER INSERT OR UPDATE ON moderacion_publicacion FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_renovacion AFTER INSERT ON renovacion_certificado FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_comision AFTER INSERT OR UPDATE ON comision_configuracion FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_parametro AFTER INSERT ON parametro_plataforma FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_merma AFTER INSERT ON motivo_merma_cambio FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_permiso AFTER INSERT ON permiso_configurable_cambio FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_tarifa AFTER INSERT ON tarifa_envio_cambio FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_capacidad AFTER INSERT ON capacidad_entrega_cambio FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
CREATE TRIGGER auditar_gobernanza_cobertura AFTER INSERT ON cobertura_entrega_cambio FOR EACH ROW EXECUTE FUNCTION auditar_gobernanza_snapshot();
