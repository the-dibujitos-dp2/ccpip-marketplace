import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fechaCorta, unidades } from '../formato.js'
import { mensajeError } from '../i18n/errores.js'
import { nuevaClave } from '../movimientos.js'
import i18n from '../i18n/index.js'
import Alerta from './Alerta.jsx'
import Campo from './Campo.jsx'
import Dialogo from './Dialogo.jsx'
import Pestanas from './Pestanas.jsx'
import './DialogoMovimiento.css'

const TIPOS = ['ENTRADA', 'SALIDA']
const CANTIDAD = /^\d{1,9}(\.\d{1,3})?$/

/**
 * CCPIP-121 (RF-59), CCPIP-287 (RF-208) y CCPIP-283 (RF-203): registra una entrada o una salida en
 * un lote, con cantidad, motivo y una observacion opcional ("Nuevo movimiento" de A-03). El
 * responsable y la fecha los pone el servidor. Se monta una vez por apertura (`key`), con su clave.
 *
 * - `lotes`: [{ id, codigo, productoId, producto, productorId?, productor?, stock, fechaVencimiento, vencido }]
 * - `motivos`: { ENTRADA: [...], SALIDA: [...] }, el catalogo del lugar. Sin ajuste de conteo ni
 *   merma: el CHANGELOG del 30/09 quito el ajuste y la merma tiene su propia accion (A-04).
 * - `conProductor`: el punto elige primero el productor; el productor ya es la sesion.
 * - `registrar(datos)` llama al API; `onRegistrado(respuesta)` recibe el movimiento y el saldo del lote.
 */
export default function DialogoMovimiento({ lotes, motivos, conProductor = false, loteInicial, registrar, onRegistrado, onCancelar }) {
  const { t } = useTranslation()
  const inicial = lotes.find((l) => l.id === loteInicial)
  const [tipo, setTipo] = useState('ENTRADA')
  const [productorId, setProductorId] = useState(inicial?.productorId ? String(inicial.productorId) : '')
  const [productoId, setProductoId] = useState(inicial ? String(inicial.productoId) : '')
  const [loteId, setLoteId] = useState(inicial ? String(inicial.id) : '')
  const [cantidad, setCantidad] = useState('')
  const [motivo, setMotivo] = useState(motivos.ENTRADA.length === 1 ? motivos.ENTRADA[0] : '')
  const [observacion, setObservacion] = useState('')
  const [clave] = useState(nuevaClave)
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  // Una entrada no va a un lote vencido; una salida solo sale de un lote con saldo
  const usables = useMemo(
    () => lotes.filter((l) => (tipo === 'ENTRADA' ? !l.vencido : Number(l.stock) > 0)),
    [lotes, tipo],
  )
  const productores = unicos(usables.filter((l) => l.productorId != null), 'productorId', 'productor')
  const productos = unicos(usables.filter((l) => !conProductor || String(l.productorId) === productorId), 'productoId', 'producto')
  const delProducto = usables.filter((l) => String(l.productoId) === productoId)
  const lote = usables.find((l) => String(l.id) === loteId)

  const valida = CANTIDAD.test(cantidad.trim()) && Number(cantidad) > 0
  const resultante = lote && valida ? Number(lote.stock) + (tipo === 'ENTRADA' ? 1 : -1) * Number(cantidad) : null

  function cambiarTipo(nuevo) {
    setTipo(nuevo)
    setMotivo(motivos[nuevo].length === 1 ? motivos[nuevo][0] : '')
    setErrores({})
    setError(null)
    // El lote elegido puede no servir para el otro tipo
    const sigue = lotes.find((l) => String(l.id) === loteId)
    if (sigue && (nuevo === 'ENTRADA' ? sigue.vencido : Number(sigue.stock) <= 0)) setLoteId('')
  }

  async function enviar(e) {
    e?.preventDefault()
    const faltan = {}
    if (!lote) faltan.loteId = t('movimiento.validacion.lote')
    if (!valida) faltan.cantidad = t('movimiento.validacion.cantidad')
    else if (tipo === 'SALIDA' && lote && Number(cantidad) > Number(lote.stock)) {
      faltan.cantidad = t('movimiento.validacion.saldo', { cantidad: unidades(lote.stock) })
    }
    if (!motivo) faltan.motivo = t('movimiento.validacion.motivo')
    setErrores(faltan)
    setError(null)
    if (Object.keys(faltan).length > 0) return

    setEnviando(true)
    try {
      onRegistrado(await registrar({
        tipo, loteId: lote.id, cantidad: cantidad.trim(), motivo, observacion: observacion.trim() || null, clave,
      }))
    } catch (err) {
      // Un 401 ya lo maneja la app (vuelve a ingresar)
      if (err.status === 401) return
      if (err.body?.campos) {
        // Los mensajes por campo llegan en espanol: en ingles se marca el campo con el mensaje del code
        const campos = err.body.campos
        setErrores(i18n.resolvedLanguage === 'es' ? campos : Object.fromEntries(Object.keys(campos).map((c) => [c, mensajeError(err)])))
      } else if (err.code === 'SALDO_INSUFICIENTE') setErrores({ cantidad: mensajeError(err) })
      else setError(err)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Dialogo
      abierto
      titulo={t('movimiento.titulo')}
      confirmar={enviando ? t('productor.lote.registrando') : t(`movimiento.registrar.${tipo}`)}
      onConfirmar={enviar}
      onCancelar={onCancelar}
      ocupado={enviando}
    >
      <form className="movimiento__formulario" onSubmit={enviar} noValidate>
        <Pestanas
          etiqueta={t('movimiento.tipoEtiqueta')}
          valor={tipo}
          onCambiar={cambiarTipo}
          opciones={TIPOS.map((v) => ({ valor: v, etiqueta: t(`movimiento.tipo.${v}`) }))}
        />
        {conProductor && (
          <Campo
            tipo="selector"
            etiqueta={t('movimiento.productor')}
            value={productorId}
            onChange={(e) => { setProductorId(e.target.value); setProductoId(''); setLoteId('') }}
            opciones={productores}
          />
        )}
        <Campo
          tipo="selector"
          etiqueta={t('movimiento.producto')}
          value={productoId}
          onChange={(e) => { setProductoId(e.target.value); setLoteId('') }}
          opciones={productos}
        />
        <Campo
          tipo="selector"
          etiqueta={t('movimiento.lote')}
          value={loteId}
          onChange={(e) => setLoteId(e.target.value)}
          error={errores.loteId}
          ayuda={productoId && delProducto.length === 0 ? t(`movimiento.sinLotes.${tipo}`) : undefined}
          opciones={delProducto.map((l) => ({ valor: String(l.id), etiqueta: etiquetaLote(l, t) }))}
        />
        <Campo
          etiqueta={t('movimiento.cantidad')}
          value={cantidad}
          onChange={(e) => { setCantidad(e.target.value); setErrores(({ cantidad: _, ...resto }) => resto) }}
          error={errores.cantidad}
          ayuda={resultante != null ? t('movimiento.resultante', { cantidad: unidades(resultante) }) : undefined}
          inputMode="decimal"
          maxLength={13}
          autoComplete="off"
        />
        <Campo
          tipo="selector"
          etiqueta={t('movimiento.motivo')}
          value={motivo}
          onChange={(e) => setMotivo(e.target.value)}
          error={errores.motivo}
          opciones={motivos[tipo].map((m) => ({ valor: m, etiqueta: t(`movimiento.motivos.${m}`) }))}
        />
        <Campo
          etiqueta={t('movimiento.observacion')}
          value={observacion}
          onChange={(e) => setObservacion(e.target.value)}
          error={errores.observacion}
          maxLength={255}
          autoComplete="off"
        />
        {error && <Alerta tono="error" titulo={t('movimiento.error')} descripcion={mensajeError(error)} />}
        {/* Enter en un campo envia el formulario, como el boton del dialogo */}
        <button type="submit" hidden />
      </form>
    </Dialogo>
  )
}

/** "L-0247 · vence 02/11/2027 · 60 und" */
function etiquetaLote(l, t) {
  const partes = [l.codigo]
  if (l.fechaVencimiento) partes.push(t('movimiento.vence', { fecha: fechaCorta(l.fechaVencimiento) }))
  partes.push(t('comun.und', { cantidad: unidades(l.stock) }))
  return partes.join(' · ')
}

/** Opciones sin repetir, en el orden de los lotes. */
function unicos(lotes, id, nombre) {
  const vistos = new Map()
  lotes.forEach((l) => { if (!vistos.has(l[id])) vistos.set(l[id], l[nombre]) })
  return [...vistos].map(([valor, etiqueta]) => ({ valor: String(valor), etiqueta }))
}
