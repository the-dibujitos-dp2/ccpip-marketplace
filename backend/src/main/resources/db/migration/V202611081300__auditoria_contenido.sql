-- CCPIP-509: eventos de contenido, solo hechos y metadatos no sensibles.
CREATE FUNCTION auditar_fuente_contenido() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb := to_jsonb(NEW); o jsonb := CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
 uid bigint; accion text; ent text; ref text; fecha timestamptz; det jsonb := '{}'::jsonb; clave text; corr text; resultado text := 'EXITOSA';
BEGIN
 IF TG_TABLE_NAME='denuncia_evento' THEN
  uid:=NEW.usuario_id; ent:='DENUNCIA'; ref:=NEW.denuncia_id::text; fecha:=NEW.registrado_en;
  accion:=CASE NEW.tipo WHEN 'REGISTRADA' THEN 'DENUNCIA_REGISTRADA' WHEN 'RESPUESTA' THEN 'DENUNCIA_RESPONDIDA' ELSE 'DENUNCIA_'||NEW.estado END;
  det:=jsonb_build_object('estado',NEW.estado); clave:='DE-'||NEW.id;
 ELSIF TG_TABLE_NAME='calificacion' THEN
  uid:=NEW.usuario_id; ent:='CALIFICACION'; ref:=NEW.id::text; fecha:=NEW.calificado_en; accion:='CALIFICACION_REGISTRADA';
  corr:='PEDIDO-'||NEW.pedido_id; det:=jsonb_build_object('estrellas',NEW.estrellas,'productoId',NEW.producto_id,'pedidoId',NEW.pedido_id); clave:='CA-'||NEW.id;
 ELSIF TG_TABLE_NAME='video_tienda' THEN
  ent:='VIDEO'; ref:=NEW.id::text;
  IF TG_OP='INSERT' THEN uid:=NEW.subido_por; fecha:=NEW.subido_en; accion:=CASE WHEN NEW.reemplaza_a IS NULL THEN 'VIDEO_SUBIDO' ELSE 'VIDEO_REEMPLAZADO' END; clave:='VS-'||NEW.id;
  ELSIF OLD.retirado_en IS NULL AND NEW.retirado_en IS NOT NULL THEN uid:=NEW.retirado_por; fecha:=NEW.retirado_en; accion:='VIDEO_RETIRADO'; clave:='VR-'||NEW.id;
  ELSIF OLD.estado IS DISTINCT FROM NEW.estado THEN uid:=NEW.decidido_por; fecha:=NEW.decidido_en; accion:='VIDEO_DECIDIDO'; clave:='MV-'||NEW.id; det:=jsonb_build_object('estado',NEW.estado);
  ELSE RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME='imagen' THEN
  IF NEW.producto_id IS NOT NULL THEN RETURN NEW; END IF;
  ent:='IMAGEN'; ref:=NEW.id::text;
  IF TG_OP='INSERT' THEN uid:=NEW.subida_por; fecha:=NEW.subida_en; accion:='GALERIA_IMAGEN_SUBIDA'; clave:='IMGS-'||NEW.id;
  ELSIF OLD.retirada_en IS NULL AND NEW.retirada_en IS NOT NULL THEN uid:=NEW.retirada_por; fecha:=NEW.retirada_en; accion:=CASE WHEN NEW.reemplazada_por IS NULL THEN 'GALERIA_IMAGEN_RETIRADA' ELSE 'GALERIA_IMAGEN_REEMPLAZADA' END; clave:='IMGR-'||NEW.id;
  ELSE RETURN NEW; END IF;
  det:=jsonb_build_object('tipo',NEW.tipo,'ancho',NEW.ancho,'alto',NEW.alto);
 ELSIF TG_TABLE_NAME='producto' THEN
  IF OLD.destacado_tienda IS NOT DISTINCT FROM NEW.destacado_tienda THEN RETURN NEW; END IF;
  SELECT usuario_id INTO uid FROM productor WHERE id=NEW.productor_id;
  ent:='PRODUCTO'; ref:=NEW.id::text; fecha:=NEW.actualizado_en; accion:='TIENDA_DESTACADO_CAMBIADO'; det:=jsonb_build_object('destacado',NEW.destacado_tienda); clave:='PD-'||NEW.id||'-'||NEW.destacado_tienda;
 ELSIF TG_TABLE_NAME='traduccion' THEN
  IF NEW.entidad NOT IN ('PRODUCTO','HISTORIA','TIENDA','COMUNIDAD') THEN RETURN NEW; END IF;
  IF NOT (TG_OP='UPDATE' AND NEW.estado='APROBADA' AND (OLD.estado IS DISTINCT FROM NEW.estado OR OLD.texto IS DISTINCT FROM NEW.texto)) THEN RETURN NEW; END IF;
  IF NEW.entidad IN ('PRODUCTO','HISTORIA') THEN SELECT pr.usuario_id INTO uid FROM producto p JOIN productor pr ON pr.id=p.productor_id WHERE p.id=NEW.clave::bigint;
  ELSE SELECT usuario_id INTO uid FROM productor WHERE id=NEW.clave::bigint; END IF;
  IF uid IS NULL THEN RETURN NEW; END IF;
  ent:=NEW.entidad; ref:=NEW.id::text; fecha:=NEW.aprobada_en; accion:='TRADUCCION_APROBADA'; det:=jsonb_build_object('tipo',NEW.entidad,'idioma',NEW.idioma,'versionOriginal',NEW.version_original); clave:='TR-'||NEW.id||'-'||NEW.version_original;
 ELSIF TG_TABLE_NAME='solicitud_unidad_medida' THEN
  ent:='SOLICITUD_UNIDAD'; ref:=NEW.id::text;
  IF TG_OP='INSERT' THEN uid:=NEW.solicitada_por; fecha:=NEW.creada_en; accion:='SOLICITUD_UNIDAD_CREADA'; clave:='SU-'||NEW.id;
  ELSIF OLD.estado='PENDIENTE' AND NEW.estado<>'PENDIENTE' THEN uid:=NEW.decidida_por; fecha:=NEW.decidida_en; accion:='SOLICITUD_UNIDAD_DECIDIDA'; det:=jsonb_build_object('estado',NEW.estado); clave:='SD-'||NEW.id;
  ELSE RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME='analisis_evidencias' THEN
  uid:=NEW.solicitado_por; fecha:=NEW.solicitado_en; ent:='ANALISIS'; ref:=NEW.id::text; accion:='ANALISIS_RESULTADO'; corr:='AUTORIZACION-'||NEW.autorizacion_id;
  resultado:=CASE NEW.estado WHEN 'COMPLETADO' THEN 'EXITOSA' ELSE 'ERROR' END;
  PERFORM registrar_evento_auditoria(uid,'AS-'||NEW.id,'ANALISIS_SOLICITADO','ANALISIS',ref,fecha,NULL,corr,'{}'::jsonb,'EXITOSA'); clave:='AN-'||NEW.id;
 ELSE RETURN NEW; END IF;
 PERFORM registrar_evento_auditoria(uid,clave,accion,ent,ref,fecha,NULL,corr,det,resultado);
 RETURN NEW;
END $$;
CREATE TRIGGER auditoria_denuncia_evento AFTER INSERT ON denuncia_evento FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
CREATE TRIGGER auditoria_calificacion AFTER INSERT ON calificacion FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
CREATE TRIGGER auditoria_video AFTER INSERT OR UPDATE ON video_tienda FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
CREATE TRIGGER auditoria_imagen AFTER INSERT OR UPDATE ON imagen FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
CREATE TRIGGER auditoria_producto_destacado AFTER UPDATE ON producto FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
CREATE TRIGGER auditoria_traduccion AFTER INSERT OR UPDATE ON traduccion FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
CREATE TRIGGER auditoria_solicitud_unidad AFTER INSERT OR UPDATE ON solicitud_unidad_medida FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
CREATE TRIGGER auditoria_analisis_evidencias AFTER INSERT ON analisis_evidencias FOR EACH ROW EXECUTE FUNCTION auditar_fuente_contenido();
