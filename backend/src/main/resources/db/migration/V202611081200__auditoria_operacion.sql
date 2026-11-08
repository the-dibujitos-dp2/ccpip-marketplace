-- Eventos operativos nuevos; sin backfill de datos históricos.
CREATE FUNCTION auditar_operacion() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE uid bigint; ref text; act text; entidad text; loc bigint; corr text; fecha timestamptz; det jsonb := '{}'::jsonb; mid bigint;
BEGIN
  IF TG_TABLE_NAME='movimiento_inventario' THEN
    uid:=CASE WHEN NEW.tipo='SALIDA_VENTA' THEN NULL ELSE NEW.usuario_id END;
    PERFORM registrar_evento_auditoria(uid,'MI-'||NEW.id,'MOVIMIENTO_'||NEW.tipo,'MOVIMIENTO',NEW.id::text,NEW.registrado_en,NEW.ubicacion_id,NULL,
      jsonb_build_object('cantidad',NEW.cantidad,'productoId',NEW.producto_id,'loteId',NEW.lote_id,'pedidoId',NEW.pedido_id,'transfItemId',NEW.transferencia_item_id));
  ELSIF TG_TABLE_NAME='pedido_estado_historial' THEN
    SELECT ubicacion_origen_id INTO loc FROM pedido WHERE id=NEW.pedido_id;
    PERFORM registrar_evento_auditoria(NEW.usuario_id,'PH-'||NEW.id,'PEDIDO_'||NEW.estado,'PEDIDO',NEW.pedido_id::text,NEW.registrado_en,loc,'PEDIDO-'||NEW.pedido_id,'{}'::jsonb);
  ELSIF TG_TABLE_NAME='entrega' THEN
    IF TG_OP<>'UPDATE' THEN RETURN NEW; END IF;
    IF OLD.asignada_en IS NULL AND NEW.asignada_en IS NOT NULL AND NEW.distribuidor_id IS NOT NULL THEN
      PERFORM registrar_evento_auditoria(NEW.distribuidor_id,'EA-'||NEW.id,'ENTREGA_ASUMIDA','PEDIDO',NEW.pedido_id::text,NEW.asignada_en,NULL,'PEDIDO-'||NEW.pedido_id,'{}'::jsonb);
    END IF;
    IF OLD.retirada_en IS NULL AND NEW.retirada_en IS NOT NULL THEN
      PERFORM registrar_evento_auditoria(NEW.distribuidor_id,'ER-'||NEW.id,'ENTREGA_RETIRADA','PEDIDO',NEW.pedido_id::text,NEW.retirada_en,NULL,'PEDIDO-'||NEW.pedido_id,'{}'::jsonb);
    END IF;
  ELSIF TG_TABLE_NAME='transferencia_historial' THEN
    SELECT destino_id INTO loc FROM transferencia WHERE id=NEW.transferencia_id;
    SELECT origen_id,destino_id INTO mid,uid FROM transferencia WHERE id=NEW.transferencia_id;
    PERFORM registrar_evento_auditoria(NEW.usuario_id,'TH-'||NEW.id,'TRANSFERENCIA_'||NEW.estado,'TRANSFERENCIA',NEW.transferencia_id::text,NEW.registrado_en,loc,'TRANSFERENCIA-'||NEW.transferencia_id,jsonb_build_object('origenId',mid,'destinoId',uid));
  ELSIF TG_TABLE_NAME='incidencia_pedido' THEN
    SELECT ubicacion_origen_id INTO loc FROM pedido WHERE id=NEW.pedido_id;
    PERFORM registrar_evento_auditoria(NEW.registrado_por,'IP-'||NEW.id,'PRODUCTO_MALOGRADO_'||NEW.tipo,'PEDIDO',NEW.pedido_id::text,NEW.registrado_en,loc,'PEDIDO-'||NEW.pedido_id,jsonb_build_object('cantidad',NEW.cantidad,'monto',NEW.monto,'comisionAnulada',NEW.comision_anulada,'loteId',NEW.lote_original_id));
  ELSIF TG_TABLE_NAME='solicitud_revision_merma' THEN
    IF TG_OP='INSERT' THEN
      SELECT lote_id INTO mid FROM merma WHERE movimiento_id=NEW.merma_movimiento_id;
      SELECT producto_id INTO uid FROM movimiento_inventario WHERE id=NEW.merma_movimiento_id;
      PERFORM registrar_evento_auditoria(NEW.solicitante_id,'SR-'||NEW.id,'REVISION_MERMA_SOLICITADA','MOVIMIENTO',NEW.merma_movimiento_id::text,NEW.registrada_en,NULL,NULL,jsonb_build_object('cantidad',NEW.cantidad,'loteId',mid,'productoId',uid));
    ELSIF OLD.resuelta_en IS NULL AND NEW.resuelta_en IS NOT NULL THEN
      PERFORM registrar_evento_auditoria(NEW.resuelta_por,'RR-'||NEW.id,'REVISION_MERMA_'||NEW.estado,'MOVIMIENTO',NEW.merma_movimiento_id::text,NEW.resuelta_en,NULL,NULL,jsonb_build_object('cantidad',NEW.cantidad,'cantidadAprobada',NEW.cantidad_aprobada,'decision',NEW.estado));
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER auditoria_movimiento_operacion AFTER INSERT ON movimiento_inventario FOR EACH ROW EXECUTE FUNCTION auditar_operacion();
CREATE TRIGGER auditoria_pedido_historial_operacion AFTER INSERT ON pedido_estado_historial FOR EACH ROW EXECUTE FUNCTION auditar_operacion();
CREATE TRIGGER auditoria_entrega_operacion AFTER UPDATE ON entrega FOR EACH ROW EXECUTE FUNCTION auditar_operacion();
CREATE TRIGGER auditoria_transferencia_historial_operacion AFTER INSERT ON transferencia_historial FOR EACH ROW EXECUTE FUNCTION auditar_operacion();
CREATE TRIGGER auditoria_incidencia_operacion AFTER INSERT ON incidencia_pedido FOR EACH ROW EXECUTE FUNCTION auditar_operacion();
CREATE TRIGGER auditoria_revision_merma_operacion AFTER INSERT OR UPDATE ON solicitud_revision_merma FOR EACH ROW EXECUTE FUNCTION auditar_operacion();
