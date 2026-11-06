import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { opcionesMalogrado, registrarMalogrado } from '../../api/acopio.js'
import { listarCausas } from '../../api/merma.js'
import Alerta from '../../components/Alerta.jsx'
import Cargando from '../../components/Cargando.jsx'
import Campo from '../../components/Campo.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import { nombreCausa } from '../../components/DialogoMerma.jsx'
import { fecha, precio, unidades } from '../../formato.js'
import { erroresPorCampo, mensajeError } from '../../i18n/errores.js'
import { nuevaClave } from '../../movimientos.js'

const CANTIDAD = /^\d{1,9}(\.\d{1,3})?$/

/** Que campo corrige cada rechazo del servidor. */
const CAMPO_POR_CODIGO = {
  CANTIDAD_EXCEDE: 'cantidad',
  CANTIDAD_MUY_PEQUENA: 'cantidad',
  LOTE_NO_ASIGNADO: 'loteId',
  LOTE_VENCIDO: 'loteReemplazoId',
  LOTE_NO_DISPONIBLE: 'loteReemplazoId',
  LOTE_YA_EN_LINEA: 'loteReemplazoId',
  MISMO_LOTE: 'loteReemplazoId',
  SALDO_INSUFICIENTE: 'loteReemplazoId',
  LOTE_REEMPLAZO_REQUERIDO: 'loteReemplazoId',
  CAUSA_INVALIDA: 'motivoId',
  DETALLE_REQUERIDO: 'detalle',
}

/** "Lote L-0247 · vence 02/11/2027 · 3 und" */
function textoLote(t, lote, cantidad) {
  const vence = lote.fechaVencimiento ? t('acopio.pedidos.vence', { fecha: fecha(lote.fechaVencimiento) }) : t('productor.lote.noVence').toLowerCase()
  return t('acopio.pedidos.lote', { codigo: lote.codigo, vence, cantidad: unidades(cantidad) })
}

/**
 * CCPIP-485 y CCPIP-486: un producto de la linea se malogro antes de entregarse. El administrador indica
 * de que lote y cuanto, la causa de la merma, y si se reemplaza con otro lote vigente del punto o, si no
 * hay, se entrega lo disponible y se reembolsa lo faltante. Se monta una vez por apertura, con su clave:
 * un reintento no registra dos veces.
 *
 * `onRegistrado(resultado)` recibe lo registrado con el pedido actualizado. `onDesactualizado(error)`
 * avisa que el pedido cambio mientras tanto (409 de estado) o ya no esta en el punto (404).
 */
export default function DialogoMalogrado({ pedido, linea, onCancelar, onRegistrado, onDesactualizado }) {
  const { t, i18n } = useTranslation()
  const [opciones, setOpciones] = useState(null)
  const [causas, setCausas] = useState(null)
  const [errorCarga, setErrorCarga] = useState(null)
  const [loteId, setLoteId] = useState('')
  const [cantidad, setCantidad] = useState('')
  const [motivoId, setMotivoId] = useState('')
  const [detalle, setDetalle] = useState('')
  const [accion, setAccion] = useState('')
  const [loteReemplazoId, setLoteReemplazoId] = useState('')
  const [clave] = useState(nuevaClave)
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  useEffect(() => {
    let vigente = true
    Promise.all([opcionesMalogrado(pedido.numero, linea.id), listarCausas()])
      .then(([o, c]) => {
        if (!vigente) return
        setOpciones(o)
        setCausas(c)
        if (o.lotes.length === 1) setLoteId(String(o.lotes[0].id))
        // Primero se intenta reemplazar (CCPIP-485); sin otro lote, solo cabe el reembolso (CCPIP-486)
        setAccion(o.reemplazos.length > 0 ? 'REEMPLAZAR' : 'REEMBOLSAR')
        if (o.reemplazos.length > 0) setLoteReemplazoId(String(o.reemplazos[0].id))
      })
      .catch((e) => { if (vigente) setErrorCarga(e) })
    return () => { vigente = false }
  }, [pedido.numero, linea.id])

  const causa = causas?.find((c) => String(c.id) === motivoId)
  const lote = opciones?.lotes.find((l) => String(l.id) === loteId)
  const valida = CANTIDAD.test(cantidad.trim()) && Number(cantidad) > 0
  const reembolso = valida && opciones ? Math.round(Number(opciones.precioUnitario) * Number(cantidad) * 100) / 100 : null
  const sinReemplazo = opciones?.reemplazos.length === 0
  const limpiar = (campo) => setErrores((x) => ({ ...x, [campo]: undefined }))

  async function enviar(e) {
    e?.preventDefault()
    const faltan = {}
    if (!lote) faltan.loteId = t('acopio.malogrado.validacion.lote')
    if (!valida) faltan.cantidad = t('merma.errores.cantidad')
    else if (lote && Number(cantidad) > Number(lote.cantidad)) faltan.cantidad = t('acopio.malogrado.validacion.excede', { cantidad: unidades(lote.cantidad) })
    if (!causa) faltan.motivoId = t('merma.errores.causa')
    else if (causa.requiereDetalle && !detalle.trim()) faltan.detalle = t('merma.errores.detalle')
    if (accion === 'REEMPLAZAR' && !loteReemplazoId) faltan.loteReemplazoId = t('acopio.malogrado.validacion.reemplazo')
    setErrores(faltan)
    setError(null)
    if (Object.keys(faltan).length > 0) return

    setEnviando(true)
    try {
      onRegistrado(await registrarMalogrado(pedido.numero, linea.id, {
        loteId: lote.id, cantidad: cantidad.trim(), motivoId: causa.id,
        detalle: causa.requiereDetalle ? detalle.trim() : null, accion,
        loteReemplazoId: accion === 'REEMPLAZAR' ? Number(loteReemplazoId) : null, clave,
      }))
    } catch (err) {
      // Un 401 ya lo maneja la app (vuelve a ingresar)
      if (err.status === 401) return
      if (err.code === 'ESTADO_INVALIDO' || err.code === 'PEDIDO_NO_ENCONTRADO' || err.code === 'LINEA_NO_ENCONTRADA') onDesactualizado(err)
      else if (err.body?.campos) setErrores(erroresPorCampo(err))
      else if (CAMPO_POR_CODIGO[err.code]) setErrores({ [CAMPO_POR_CODIGO[err.code]]: mensajeError(err) })
      else setError(err)
    } finally {
      setEnviando(false)
    }
  }

  const listo = opciones && causas
  return (
    <DialogoFormulario
      titulo={t('acopio.malogrado.titulo', { producto: linea.producto, numero: pedido.numero })}
      confirmar={t(accion === 'REEMBOLSAR' ? 'acopio.malogrado.confirmarReembolso' : 'acopio.malogrado.confirmarReemplazo')}
      enviando={enviando}
      onEnviar={enviar}
      onCancelar={onCancelar}
      error={error}
      tituloError={t('acopio.malogrado.error')}
    >
      {errorCarga && <Alerta tono="error" titulo={t('acopio.malogrado.errorCargar')} descripcion={mensajeError(errorCarga, 'comun.errorConexion')} />}
      {!listo && !errorCarga && <Cargando />}
      {listo && (
        <>
          <p className="body-s">{t('acopio.malogrado.instrucciones', { cantidad: unidades(opciones.porEntregar) })}</p>
          <Campo tipo="selector" etiqueta={t('acopio.malogrado.lote')} placeholder={t('acopio.malogrado.elegir')}
            value={loteId} error={errores.loteId}
            onChange={(e) => { setLoteId(e.target.value); limpiar('loteId') }}
            opciones={opciones.lotes.map((l) => ({ valor: String(l.id), etiqueta: textoLote(t, l, l.cantidad) }))} />
          <Campo etiqueta={t('acopio.malogrado.cantidad')} inputMode="decimal" value={cantidad} error={errores.cantidad}
            onChange={(e) => { setCantidad(e.target.value); limpiar('cantidad') }} autoComplete="off" />
          <Campo tipo="selector" etiqueta={t('merma.causa')} placeholder={t('acopio.malogrado.elegir')}
            value={motivoId} error={errores.motivoId}
            onChange={(e) => { setMotivoId(e.target.value); limpiar('motivoId') }}
            opciones={causas.map((c) => ({ valor: String(c.id), etiqueta: nombreCausa(c, i18n) }))} />
          {causa?.requiereDetalle && (
            <Campo etiqueta={t('merma.detalle')} value={detalle} maxLength={200} error={errores.detalle}
              onChange={(e) => { setDetalle(e.target.value); limpiar('detalle') }} />
          )}
          <Campo tipo="selector" etiqueta={t('acopio.malogrado.accion')} value={accion}
            onChange={(e) => { setAccion(e.target.value); limpiar('loteReemplazoId') }}
            ayuda={sinReemplazo ? t('acopio.malogrado.sinReemplazo') : undefined}
            opciones={[
              ...(sinReemplazo ? [] : [{ valor: 'REEMPLAZAR', etiqueta: t('acopio.malogrado.accionOpcion.REEMPLAZAR') }]),
              { valor: 'REEMBOLSAR', etiqueta: t('acopio.malogrado.accionOpcion.REEMBOLSAR') },
            ]} />
          {accion === 'REEMPLAZAR' && (
            <Campo tipo="selector" etiqueta={t('acopio.malogrado.loteReemplazo')} value={loteReemplazoId} error={errores.loteReemplazoId}
              onChange={(e) => { setLoteReemplazoId(e.target.value); limpiar('loteReemplazoId') }}
              opciones={opciones.reemplazos.map((l) => ({ valor: String(l.id), etiqueta: textoLote(t, l, l.disponible) }))} />
          )}
          <Alerta tono="aviso" titulo={accion === 'REEMBOLSAR'
            ? t('acopio.malogrado.resumenReembolso', { monto: reembolso == null ? '—' : precio(reembolso) })
            : t('acopio.malogrado.resumenReemplazo')}
            descripcion={t('acopio.malogrado.irreversible')} />
        </>
      )}
    </DialogoFormulario>
  )
}
