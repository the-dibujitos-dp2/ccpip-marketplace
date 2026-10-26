import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import { consultarReporte } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fecha, mesAnio, capital, precio } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'
import './ReportesPage.css'

/** Metricas de SA-05, en el orden de las pestanas. Cada una es un endpoint /api/admin/reportes/{clave}. */
const METRICAS = ['ventas', 'comisiones', 'ingresos']

const AGRUPACIONES = ['DIA', 'SEMANA', 'MES']

/**
 * SA-05 Reportes de la plataforma (superadministrador). CCPIP-345 (RF-235), CCPIP-346 (RF-236) y CCPIP-93
 * (RF-71): ventas, comisiones e ingresos acumulados por periodo con su desglose B2C (tarifa minorista) y B2B (tarifa mayorista). Solo cifras agregadas: el
 * servidor no envia compradores ni el detalle de cada productor (SA-08).
 */
export default function ReportesPage({ path }) {
  const { t } = useTranslation()
  const [metrica, setMetrica] = useState(METRICAS[0])
  const [filtro, setFiltro] = useState({ desde: '', hasta: '', agrupacion: '' })
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)

  const consultar = useCallback(async (clave, valores) => {
    setCargando(true)
    setError(null)
    try {
      const respuesta = await consultarReporte(clave, valores)
      setDatos(respuesta)
      setFiltro({ desde: respuesta.desde, hasta: respuesta.hasta, agrupacion: respuesta.agrupacion })
    } catch (e) {
      setError(e)
    } finally {
      setCargando(false)
    }
  }, [])

  useEffect(() => {
    consultar(METRICAS[0])
  }, [consultar])

  const cambiar = (campo) => (e) => {
    const nuevo = { ...filtro, [campo]: e.target.value }
    setFiltro(nuevo)
    if (nuevo.desde && nuevo.hasta && nuevo.agrupacion) consultar(metrica, nuevo)
  }

  function elegirMetrica(clave) {
    setMetrica(clave)
    consultar(clave, filtro.desde && filtro.hasta ? filtro : undefined)
  }

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.reportes')} titulo={t('admin.reportes.titulo')}>
      {METRICAS.length > 1 && (
        <Pestanas
          etiqueta={t('admin.reportes.titulo')}
          valor={metrica}
          onCambiar={elegirMetrica}
          opciones={METRICAS.map((m) => ({ valor: m, etiqueta: t(`admin.reportes.metrica.${m}.pestana`) }))}
        />
      )}
      <div className="reportes__filtros">
        <Campo etiqueta={t('admin.reportes.desde')} type="date" value={filtro.desde} max={filtro.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('admin.reportes.hasta')} type="date" value={filtro.hasta} min={filtro.desde || undefined} onChange={cambiar('hasta')} />
        <Campo
          etiqueta={t('admin.reportes.agrupacion')}
          tipo="selector"
          value={filtro.agrupacion}
          onChange={cambiar('agrupacion')}
          opciones={AGRUPACIONES.map((a) => ({ valor: a, etiqueta: t(`admin.reportes.por.${a}`) }))}
        />
      </div>
      {error && <Alerta tono="error" titulo={t('admin.reportes.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {cargando && !datos && <Cargando />}
      {datos && <Resultado datos={datos} />}
    </LayoutAdmin>
  )
}

/** Nombre del periodo: el mes, la semana desde su lunes o el dia. */
function nombrePeriodo(p, agrupacion, t) {
  if (agrupacion === 'MES') return capital(mesAnio(p.inicio))
  if (agrupacion === 'SEMANA') return t('admin.reportes.semana', { inicio: fecha(p.inicio), fin: fecha(p.fin) })
  return fecha(p.inicio)
}

function Resultado({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, agrupacion } = datos
  // Los textos siguen a la metrica que respondio el servidor, no a la pestana que se acaba de pulsar
  const clave = `admin.reportes.metrica.${datos.metrica.toLowerCase()}`
  const mayor = Math.max(...periodos.map((p) => Number(p.total)), 0)
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t(`${clave}.total`)} valor={precio(totales.total)} tono="neutro" icono={iconoGrafico}
          detalle={t('admin.reportes.rango', { desde: fecha(datos.desde), hasta: fecha(datos.hasta) })} />
        <TarjetaKpi titulo={t('admin.reportes.b2c')} valor={precio(totales.b2c)} icono={iconoTienda} detalle={t('admin.reportes.b2cDetalle')} tono="neutro" />
        <TarjetaKpi titulo={t('admin.reportes.b2b')} valor={precio(totales.b2b)} icono={iconoComunidad} detalle={t('admin.reportes.b2bDetalle')} tono="neutro" />
      </div>
      <section className="productor__seccion" aria-labelledby="reportes-tabla">
        <h2 id="reportes-tabla" className="heading-h3">{t(`${clave}.tabla`)}</h2>
        <div className="tabla" role="table" aria-labelledby="reportes-tabla">
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda reportes__periodo" role="columnheader">{t('admin.reportes.col.periodo')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.reportes.col.barra')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.reportes.col.b2c')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.reportes.col.b2b')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.reportes.col.total')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.reportes.col.acumulado')}</span>
          </div>
          {periodos.map((p) => (
            <div key={p.inicio} className="tabla__fila" role="row">
              <span className="tabla__celda reportes__periodo label-m" role="cell">{nombrePeriodo(p, agrupacion, t)}</span>
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <Barra b2c={Number(p.b2c)} b2b={Number(p.b2b)} mayor={mayor} />
              </span>
              <span className="tabla__celda admin__num" role="cell">{precio(p.b2c)}</span>
              <span className="tabla__celda admin__num" role="cell">{precio(p.b2b)}</span>
              <span className="tabla__celda admin__num" role="cell">{precio(p.total)}</span>
              <span className="tabla__celda admin__num label-m" role="cell">{precio(p.acumulado)}</span>
            </div>
          ))}
        </div>
        <p className="caption">{t(`${clave}.nota`)}</p>
      </section>
    </>
  )
}

/** Barra apilada B2C y B2B del periodo, a escala del periodo mayor. Decorativa: las cifras estan en la fila. */
function Barra({ b2c, b2b, mayor }) {
  if (!mayor) return null
  return (
    <span className="reportes__barra" aria-hidden="true">
      <span className="reportes__barra-b2c" style={{ width: `${(b2c / mayor) * 100}%` }} />
      <span className="reportes__barra-b2b" style={{ width: `${(b2b / mayor) * 100}%` }} />
    </span>
  )
}
