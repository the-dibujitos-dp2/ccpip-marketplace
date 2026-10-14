import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check.svg'
import { listarUbicaciones, registrarLote } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { fechaCorta, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import './Lotes.css'

const TONO = { VENDE_PRIMERO: 'error', DISPONIBLE: 'exito', EN_ALMACEN: 'info', AGOTADO: 'neutro', VENCIDO: 'neutro' }

function hoyLima() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'America/Lima' })
}

/** Formulario "Nuevo lote" de PW-06 (CCPIP-41). Valida en pantalla lo mismo que el servidor. */
export function FormularioLote({ productoId, requiereVencimiento, onRegistrado, sugerencia }) {
  const { t } = useTranslation()
  const [ubicaciones, setUbicaciones] = useState([])
  const [datos, setDatos] = useState({ codigo: sugerencia ?? '', cantidad: '', fechaProduccion: '', fechaVencimiento: '', ubicacionId: '' })
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)

  useEffect(() => {
    listarUbicaciones().then(setUbicaciones).catch(() => setUbicaciones([]))
  }, [])
  useEffect(() => setDatos((d) => ({ ...d, codigo: sugerencia ?? d.codigo })), [sugerencia])

  const cambiar = (campo) => (e) => setDatos({ ...datos, [campo]: e.target.value })
  const hoy = hoyLima()

  async function enviar(e) {
    e.preventDefault()
    const nuevos = {}
    if (!datos.codigo.trim()) nuevos.codigo = t('productor.lote.validacion.codigo')
    if (!(Number(datos.cantidad) > 0)) nuevos.cantidad = t('productor.lote.validacion.cantidad')
    if (requiereVencimiento && !datos.fechaVencimiento) nuevos.fechaVencimiento = t('productor.lote.validacion.vencimiento')
    if (datos.fechaVencimiento && datos.fechaVencimiento < hoy) nuevos.fechaVencimiento = t('productor.lote.validacion.anteriorHoy')
    if (datos.fechaProduccion && datos.fechaProduccion > hoy) nuevos.fechaProduccion = t('productor.lote.validacion.posteriorHoy')
    if (!datos.ubicacionId) nuevos.ubicacionId = t('productor.lote.validacion.ubicacion')
    setErrores(nuevos)
    if (Object.keys(nuevos).length) return

    setEnviando(true)
    setAlerta(null)
    try {
      const lotes = await registrarLote(productoId, {
        codigo: datos.codigo.trim(),
        cantidad: datos.cantidad,
        fechaProduccion: datos.fechaProduccion || null,
        fechaVencimiento: datos.fechaVencimiento || null,
        ubicacionId: Number(datos.ubicacionId),
      })
      setDatos({ codigo: '', cantidad: '', fechaProduccion: '', fechaVencimiento: '', ubicacionId: datos.ubicacionId })
      setAlerta({ tono: 'exito', titulo: t('productor.lote.registrado'), descripcion: t('productor.lote.registradoTexto') })
      onRegistrado?.(lotes)
    } catch (err) {
      // Los mensajes por campo llegan del servidor en espanol: en ingles basta el del code
      const campos = i18n.resolvedLanguage === 'es' && err.body?.campos ? Object.values(err.body.campos).join(' · ') : undefined
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.lote.error'), descripcion: campos })
    } finally {
      setEnviando(false)
    }
  }

  const ubicacionElegida = ubicaciones.find((u) => String(u.id) === datos.ubicacionId)

  return (
    <form className="formulario-lote" onSubmit={enviar} noValidate>
      <h2 className="heading-h2">{t('productor.lote.nuevo')}</h2>
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      <div className="formulario-lote__fila">
        <Campo etiqueta={t('productor.lote.codigo')} value={datos.codigo} onChange={cambiar('codigo')} error={errores.codigo} maxLength={40} placeholder="L-0001" />
        <Campo etiqueta={t('selectorCantidad.etiqueta')} type="number" inputMode="decimal" min="0" step="any" value={datos.cantidad} onChange={cambiar('cantidad')} error={errores.cantidad} />
      </div>
      <div className="formulario-lote__fila">
        <Campo etiqueta={t('productor.lote.produccion')} type="date" max={hoy} value={datos.fechaProduccion} onChange={cambiar('fechaProduccion')} error={errores.fechaProduccion} />
        <Campo
          etiqueta={requiereVencimiento ? t('productor.lote.vencimiento') : t('productor.lote.vencimientoOpcional')}
          type="date"
          min={hoy}
          value={datos.fechaVencimiento}
          onChange={cambiar('fechaVencimiento')}
          error={errores.fechaVencimiento}
          ayuda={requiereVencimiento ? undefined : t('productor.lote.lineaNoVence')}
        />
      </div>
      <Campo
        etiqueta={t('productor.lote.ingresaA')}
        tipo="selector"
        placeholder={t('productor.lote.ubicacionPlaceholder')}
        value={datos.ubicacionId}
        onChange={cambiar('ubicacionId')}
        error={errores.ubicacionId}
        opciones={ubicaciones.map((u) => ({ valor: String(u.id), etiqueta: u.tipo === 'ALMACEN_PRODUCTOR' ? t('productor.lote.miAlmacenEn', { departamento: u.departamento }) : t('productor.lote.puntoDeAcopio', { nombre: u.nombre }) }))}
        ayuda={
          ubicacionElegida?.tipo === 'ALMACEN_PRODUCTOR'
            ? t('productor.lote.ayudaAlmacen')
            : t('productor.lote.ayudaAcopio')
        }
      />
      <Boton type="submit" anchoCompleto icono={iconoCheck} disabled={enviando}>
        {enviando ? t('productor.lote.registrando') : t('productor.panel.registrarLote')}
      </Boton>
    </form>
  )
}

/** "Lotes de este producto" de PW-06. El codigo solo lo ve el productor. */
export function TablaLotes({ lotes }) {
  const { t } = useTranslation()
  if (!lotes?.items.length) {
    return <p className="body-s">{t('productor.lote.sinLotes')}</p>
  }
  return (
    <div className="tabla tabla--lotes">
      <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
        <span className="tabla__celda lotes__col">{t('productor.lote.tabla.lote')}</span>
        <span className="tabla__celda lotes__col">{t('productor.lote.tabla.vence')}</span>
        <span className="tabla__celda lotes__col-corta">{t('productor.lote.tabla.stock')}</span>
        <span className="tabla__celda tabla__celda--flex">{t('productor.lote.tabla.punto')}</span>
        <span className="tabla__celda lotes__col-estado">{t('productor.lote.tabla.estado')}</span>
      </div>
      <ul className="productos__lista" aria-label={t('productor.lotes.deEsteProducto')}>
        {lotes.items.map((l) => (
          <li key={l.id} className="tabla__fila">
            <span className="tabla__celda lotes__col label-m">{l.codigo}</span>
            <span className="tabla__celda lotes__col">{l.fechaVencimiento ? fechaCorta(l.fechaVencimiento) : t('productor.lote.noVence')}</span>
            <span className="tabla__celda lotes__col-corta">{unidades(l.cantidadActual)}</span>
            <span className="tabla__celda tabla__celda--flex lotes__punto">{l.tipoUbicacion === 'ALMACEN_PRODUCTOR' ? t('productor.lote.miAlmacen') : l.ubicacion}</span>
            <span className="tabla__celda lotes__col-estado">
              <Badge tono={TONO[l.estado]}>{t(`productor.lote.estado.${l.estado}`)}</Badge>
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
