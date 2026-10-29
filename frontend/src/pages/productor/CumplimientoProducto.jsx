import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarCumplimiento, declararCumplimiento } from '../../api/autorizaciones.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Interruptor from '../../components/Interruptor.jsx'
import { fechaHora } from '../../formato.js'
import i18n from '../../i18n/index.js'
import { mensajeError } from '../../i18n/errores.js'

const enIngles = () => i18n.resolvedLanguage === 'en'

/**
 * CCPIP-34 (RF-11): el productor declara, para este producto, cuales reglas de origen y sostenibilidad de
 * la Camara cumple. Ninguna se asume: parte sin marcar y puede declarar ninguna. Cada declaracion queda con
 * su fecha; una nueva reemplaza a la anterior. Declarar no es acreditar ni da sellos (CCPIP-38).
 */
export default function CumplimientoProducto({ productoId }) {
  const { t } = useTranslation()
  const c = (k, o) => t(`cumplimiento.${k}`, o)
  const consultar = useCallback(() => consultarCumplimiento(productoId), [productoId])
  const { datos, setDatos, error } = useConsulta(consultar)
  // null: lo declarado (o nada, si nunca declaro); un arreglo: lo que el productor esta cambiando
  const [cambios, setCambios] = useState(null)
  const [observaciones, setObservaciones] = useState('')
  const [aviso, setAviso] = useState(null)
  const [guardando, setGuardando] = useState(false)

  if (error || !datos) return null

  const marcadas = cambios ?? datos.vigente?.reglas ?? []
  const nombre = (codigo) => {
    const regla = datos.reglas.find((r) => r.codigo === codigo)
    if (!regla) return codigo
    return enIngles() ? regla.nombreEn : regla.nombre
  }
  const cambiar = (codigo) => (activo) =>
    setCambios(activo ? [...marcadas, codigo] : marcadas.filter((x) => x !== codigo))

  async function guardar() {
    setGuardando(true)
    try {
      // En el orden del catalogo de la Camara
      const reglas = datos.reglas.map((r) => r.codigo).filter((x) => marcadas.includes(x))
      setDatos(await declararCumplimiento(productoId, { reglas, observaciones: observaciones.trim() }))
      setCambios(null)
      setObservaciones('')
      setAviso({ tono: 'exito', titulo: c('guardada') })
    } catch (e) {
      setAviso({ tono: 'error', titulo: mensajeError(e, 'cumplimiento.error') })
    } finally {
      setGuardando(false)
    }
  }

  const vigente = datos.vigente
  return (
    <section className="tarjeta-panel cumplimiento" aria-labelledby="cumplimiento-titulo">
      <h2 id="cumplimiento-titulo" className="heading-h4">{c('titulo')}</h2>
      <p className="body-s">{c('texto')}</p>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      <p className="caption">
        {vigente
          ? c('ultima', { fecha: fechaHora(vigente.declaradaEn), nombre: vigente.declaradaPor })
          : c('sinDeclaracion')}
      </p>
      <ul className="cumplimiento__reglas" aria-label={c('reglas')}>
        {datos.reglas.map((r) => {
          const etiqueta = enIngles() ? r.nombreEn : r.nombre
          const descripcion = enIngles() ? r.descripcionEn : r.descripcion
          return (
            <li key={r.codigo}>
              <Interruptor etiqueta={etiqueta} activo={marcadas.includes(r.codigo)} onCambiar={cambiar(r.codigo)} />
              {/* CCPIP-438: si la descripcion solo repite el nombre, no se muestra dos veces */}
              {descripcion && descripcion.trim() !== etiqueta.trim() && <p className="caption">{descripcion}</p>}
            </li>
          )
        })}
      </ul>
      <Campo tipo="area" etiqueta={c('observaciones')} value={observaciones} maxLength={500}
        onChange={(e) => setObservaciones(e.target.value)} />
      <Boton tamano="M" onClick={guardar} cargando={guardando}>{c('declarar')}</Boton>
      <p className="caption">{c('nota')}</p>

      {datos.historial.length > 1 && (
        <details className="cumplimiento__historial">
          <summary className="label-m">{c('historial')}</summary>
          <ul>
            {datos.historial.slice(1).map((d) => (
              <li key={d.id} className="body-s">
                {fechaHora(d.declaradaEn)} · {d.reglas.length ? d.reglas.map(nombre).join(', ') : c('ninguna')}
                {d.observaciones && ` · «${d.observaciones}»`}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}
