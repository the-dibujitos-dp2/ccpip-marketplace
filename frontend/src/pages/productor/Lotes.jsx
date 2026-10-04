import { useEffect, useState } from 'react'
import iconoCheck from '../../assets/icons/check.svg'
import { listarUbicaciones, registrarLote } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { fechaCorta } from '../../formato.js'
import './Lotes.css'

const unidades = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 3 })

const ESTADO = {
  VENDE_PRIMERO: { tono: 'error', texto: 'Vende primero' },
  DISPONIBLE: { tono: 'exito', texto: 'Disponible' },
  EN_ALMACEN: { tono: 'info', texto: 'En tu almacén' },
  AGOTADO: { tono: 'neutro', texto: 'Agotado' },
  VENCIDO: { tono: 'neutro', texto: 'Vencido' },
}

function hoyLima() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'America/Lima' })
}

/** Formulario "Nuevo lote" de PW-06 (CCPIP-41). Valida en pantalla lo mismo que el servidor. */
export function FormularioLote({ productoId, requiereVencimiento, onRegistrado, sugerencia }) {
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
    if (!datos.codigo.trim()) nuevos.codigo = 'Escribe el código del lote'
    if (!(Number(datos.cantidad) > 0)) nuevos.cantidad = 'La cantidad debe ser mayor a cero'
    if (requiereVencimiento && !datos.fechaVencimiento) nuevos.fechaVencimiento = 'Este producto necesita fecha de vencimiento'
    if (datos.fechaVencimiento && datos.fechaVencimiento < hoy) nuevos.fechaVencimiento = 'No puede ser anterior a hoy'
    if (datos.fechaProduccion && datos.fechaProduccion > hoy) nuevos.fechaProduccion = 'No puede ser posterior a hoy'
    if (!datos.ubicacionId) nuevos.ubicacionId = 'Elige dónde ingresa el lote'
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
      setAlerta({ tono: 'exito', titulo: 'Lote registrado', descripcion: 'El stock se actualizó con su movimiento de entrada.' })
      onRegistrado?.(lotes)
    } catch (err) {
      setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos registrar el lote', descripcion: err.body?.campos ? Object.values(err.body.campos).join(' · ') : undefined })
    } finally {
      setEnviando(false)
    }
  }

  const ubicacionElegida = ubicaciones.find((u) => String(u.id) === datos.ubicacionId)

  return (
    <form className="formulario-lote" onSubmit={enviar} noValidate>
      <h2 className="heading-h2">Nuevo lote</h2>
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      <div className="formulario-lote__fila">
        <Campo etiqueta="Código de lote" value={datos.codigo} onChange={cambiar('codigo')} error={errores.codigo} maxLength={40} placeholder="L-0001" />
        <Campo etiqueta="Cantidad" type="number" inputMode="decimal" min="0" step="any" value={datos.cantidad} onChange={cambiar('cantidad')} error={errores.cantidad} />
      </div>
      <div className="formulario-lote__fila">
        <Campo etiqueta="Producción" type="date" max={hoy} value={datos.fechaProduccion} onChange={cambiar('fechaProduccion')} error={errores.fechaProduccion} />
        <Campo
          etiqueta={requiereVencimiento ? 'Vencimiento' : 'Vencimiento (opcional)'}
          type="date"
          min={hoy}
          value={datos.fechaVencimiento}
          onChange={cambiar('fechaVencimiento')}
          error={errores.fechaVencimiento}
          ayuda={requiereVencimiento ? undefined : 'Esta línea no vence'}
        />
      </div>
      <Campo
        etiqueta="Ingresa a"
        tipo="selector"
        placeholder="Elige tu almacén o un punto de acopio"
        value={datos.ubicacionId}
        onChange={cambiar('ubicacionId')}
        error={errores.ubicacionId}
        opciones={ubicaciones.map((u) => ({ valor: String(u.id), etiqueta: u.tipo === 'ALMACEN_PRODUCTOR' ? `Mi almacén · ${u.departamento}` : `${u.nombre} · punto de acopio` }))}
        ayuda={
          ubicacionElegida?.tipo === 'ALMACEN_PRODUCTOR'
            ? 'Lo que está en tu almacén se vende después de transferirlo a un punto de acopio.'
            : 'En un punto de acopio el lote queda en venta de inmediato.'
        }
      />
      <Boton type="submit" anchoCompleto icono={iconoCheck} disabled={enviando}>
        {enviando ? 'Registrando…' : 'Registrar lote'}
      </Boton>
    </form>
  )
}

/** "Lotes de este producto" de PW-06. El codigo solo lo ve el productor. */
export function TablaLotes({ lotes }) {
  if (!lotes?.items.length) {
    return <p className="body-s">Aún no hay lotes registrados para este producto.</p>
  }
  return (
    <div className="tabla tabla--lotes">
      <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
        <span className="tabla__celda lotes__col">Lote</span>
        <span className="tabla__celda lotes__col">Vence</span>
        <span className="tabla__celda lotes__col-corta">Stock</span>
        <span className="tabla__celda tabla__celda--flex">Punto</span>
        <span className="tabla__celda lotes__col-estado">Estado</span>
      </div>
      <ul className="productos__lista" aria-label="Lotes de este producto">
        {lotes.items.map((l) => (
          <li key={l.id} className="tabla__fila">
            <span className="tabla__celda lotes__col label-m">{l.codigo}</span>
            <span className="tabla__celda lotes__col">{l.fechaVencimiento ? fechaCorta(l.fechaVencimiento) : 'No vence'}</span>
            <span className="tabla__celda lotes__col-corta">{unidades.format(l.cantidadActual)}</span>
            <span className="tabla__celda tabla__celda--flex lotes__punto">{l.tipoUbicacion === 'ALMACEN_PRODUCTOR' ? 'Mi almacén' : l.ubicacion}</span>
            <span className="tabla__celda lotes__col-estado">
              <Badge tono={ESTADO[l.estado].tono}>{ESTADO[l.estado].texto}</Badge>
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
