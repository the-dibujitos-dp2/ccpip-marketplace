import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check.svg'
import { consultarReporteAcopio, urlReporteAcopio } from '../../api/acopio.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import Icono from '../../components/Icono.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import '../../components/Chips.css'
import './ReportesPage.css'
import Entradas from './reportes/Entradas.jsx'
import Inventario from './reportes/Inventario.jsx'
import Salidas from './reportes/Salidas.jsx'
import Ventas from './reportes/Ventas.jsx'

/** Reportes de la pantalla, en orden. Cada uno es /api/acopio/reportes/{clave}. */
const REPORTES = { entradas: Entradas, salidas: Salidas, ventas: Ventas, inventario: Inventario }

const AGRUPACIONES = ['DIA', 'SEMANA', 'MES']

/** Formatos de exportacion: los cuatro reportes juntos, con los filtros que se ven. */
const FORMATOS = ['pdf', 'xlsx']

/**
 * A-06 · Reportes del administrador de punto de acopio: lo que paso en sus puntos por periodo, con los
 * filtros de fechas, agrupacion, productor y producto. El servidor solo envia lo de sus puntos (RNF-05) y
 * ningun dato de los compradores; la pantalla solo muestra lo calculado.
 * - CCPIP-117 (RF-65): entradas
 * - CCPIP-341 (RF-231): salidas
 * - CCPIP-342 (RF-232): ventas
 * - CCPIP-343 (RF-233): inventario
 * - CCPIP-325, CCPIP-378, CCPIP-380 y CCPIP-382: los cuatro, juntos en PDF
 * - CCPIP-377, CCPIP-379, CCPIP-381 y CCPIP-383: los cuatro, juntos en Excel
 */
export default function ReportesPage({ path }) {
  const { t, i18n } = useTranslation()
  const claves = Object.keys(REPORTES)
  const [reporte, setReporte] = useState(claves[0])
  const [filtro, setFiltro] = useState({ desde: '', hasta: '', agrupacion: '', productor: '', producto: '' })
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)

  const consultar = useCallback(async (clave, valores) => {
    setCargando(true)
    setError(null)
    try {
      const respuesta = await consultarReporteAcopio(clave, valores)
      setDatos({ ...respuesta, clave })
      setFiltro({
        desde: respuesta.desde,
        hasta: respuesta.hasta,
        agrupacion: respuesta.agrupacion,
        productor: respuesta.productor == null ? '' : String(respuesta.productor),
        producto: respuesta.producto == null ? '' : String(respuesta.producto),
      })
    } catch (e) {
      setError(e)
    } finally {
      setCargando(false)
    }
  }, [])

  useEffect(() => {
    consultar(Object.keys(REPORTES)[0])
  }, [consultar])

  function aplicar(nuevo) {
    setFiltro(nuevo)
    if (nuevo.desde && nuevo.hasta) consultar(reporte, nuevo)
  }

  const cambiar = (campo) => (e) => aplicar({ ...filtro, [campo]: e.target.value })

  function elegirReporte(clave) {
    setReporte(clave)
    consultar(clave, filtro.desde && filtro.hasta ? filtro : undefined)
  }

  // Lo que se ve sigue al reporte que respondio el servidor, no al que se acaba de elegir
  const Reporte = datos ? REPORTES[datos.clave] : null

  return (
    <LayoutAcopio path={path} punto={datos?.puntos} titulo={t('acopio.nav.reportes')}>
      <div className="reportes-acopio__opciones">
        <Opciones
          etiqueta={t('acopio.reportes.reporteDe')}
          valor={reporte}
          onCambiar={elegirReporte}
          opciones={claves.map((c) => ({ valor: c, etiqueta: t(`acopio.reportes.${c}.pestana`) }))}
        />
        <Opciones
          etiqueta={t('acopio.reportes.periodo')}
          valor={filtro.agrupacion}
          onCambiar={(agrupacion) => aplicar({ ...filtro, agrupacion })}
          opciones={AGRUPACIONES.map((a) => ({ valor: a, etiqueta: t(`acopio.reportes.por.${a}`) }))}
        />
      </div>
      <div className="reportes-acopio__filtros">
        <Campo etiqueta={t('acopio.reportes.desde')} type="date" value={filtro.desde} max={filtro.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('acopio.reportes.hasta')} type="date" value={filtro.hasta} min={filtro.desde || undefined} onChange={cambiar('hasta')} />
        <Campo
          etiqueta={t('acopio.reportes.productor')}
          className="reportes-acopio__selector"
          tipo="selector"
          placeholder={t('acopio.reportes.todos')}
          value={filtro.productor}
          onChange={cambiar('productor')}
          opciones={(datos?.productores ?? []).map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
        />
        <Campo
          etiqueta={t('acopio.reportes.producto')}
          className="reportes-acopio__selector"
          tipo="selector"
          placeholder={t('acopio.reportes.todos')}
          value={filtro.producto}
          onChange={cambiar('producto')}
          opciones={(datos?.productos ?? []).map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
        />
        {datos && (
          <div className="reportes-acopio__exportar">
            {FORMATOS.map((f) => (
              <a key={f} className="boton boton--secundario boton--M" download
                href={urlReporteAcopio(f, datos, i18n.resolvedLanguage === 'en' ? 'en' : 'es')}>
                {t(`acopio.reportes.exportar.${f}`)}
              </a>
            ))}
          </div>
        )}
      </div>
      {error && <Alerta tono="error" titulo={t('acopio.reportes.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {cargando && !datos && <p className="caption">{t('comun.cargando')}</p>}
      {Reporte && <Reporte datos={datos} />}
    </LayoutAcopio>
  )
}

/** Eleccion unica con chips, como «Reporte de» y «Periodo» en A-06. */
function Opciones({ etiqueta, opciones, valor, onCambiar }) {
  return (
    <fieldset className="chips reportes-acopio__chips">
      <legend className="campo__etiqueta">{etiqueta}</legend>
      <div className="chips__lista">
        {opciones.map((o) => {
          const activo = o.valor === valor
          return (
            <button key={o.valor} type="button" className={`chip${activo ? ' chip--activo' : ''}`} aria-pressed={activo}
              onClick={() => !activo && onCambiar(o.valor)}>
              {activo && <Icono src={iconoCheck} tamano={14} />}
              {o.etiqueta}
            </button>
          )
        })}
      </div>
    </fieldset>
  )
}
