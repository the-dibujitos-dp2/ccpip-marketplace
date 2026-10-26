import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import { consultarReporte } from '../../api/admin.js'
import { useReporte } from '../../api/useReporte.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import { Barra } from '../../components/TablaReporte.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fecha, mesAnio, capital, precio } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'
import './ReportesPage.css'
import Productores from './reportes/Productores.jsx'
import Puntos from './reportes/Puntos.jsx'

/** Metricas de SA-05, en el orden de las pestanas. Cada una es un endpoint /api/admin/reportes/{clave}. */
const METRICAS = ['ventas', 'comisiones', 'ingresos']

/** Reportes de gestion (SA-10), despues de las metricas: cada uno es /api/admin/reportes/{clave}. */
const GESTION = { productores: Productores, puntos: Puntos }

const PESTANAS = [...METRICAS, ...Object.keys(GESTION)]

const AGRUPACIONES = ['DIA', 'SEMANA', 'MES']

const FILTRO_VACIO = { desde: '', hasta: '', agrupacion: '' }

/** Los filtros que aplico el servidor; un reporte sin agrupacion conserva la elegida. */
const aFiltro = (r, anterior) => ({ ...anterior, desde: r.desde, hasta: r.hasta, agrupacion: r.agrupacion ?? anterior.agrupacion })

/** Solo las metricas por periodo se agrupan. */
const seAgrupa = (clave) => METRICAS.includes(clave)

/**
 * SA-05 y SA-10 Reportes de la plataforma (superadministrador). Solo cifras agregadas: el servidor no envia
 * compradores (SA-08).
 * - CCPIP-345 (RF-235), CCPIP-346 (RF-236) y CCPIP-93 (RF-71): ventas, comisiones e ingresos acumulados por
 *   periodo con su desglose B2C (tarifa minorista) y B2B (tarifa mayorista)
 * - CCPIP-122 (RF-86): productores que mas venden
 * - CCPIP-355 (RF-245) y CCPIP-356 (RF-246): ventas y denuncias por punto de acopio
 */
export default function ReportesPage({ path }) {
  const { t } = useTranslation()
  const [pestana, setPestana] = useState(PESTANAS[0])
  const { filtro, setFiltro, datos, error, cargando, cargar } = useReporte(consultarReporte, aFiltro, PESTANAS[0], FILTRO_VACIO)

  const cambiar = (campo) => (e) => {
    const nuevo = { ...filtro, [campo]: e.target.value }
    setFiltro(nuevo)
    if (nuevo.desde && nuevo.hasta) cargar(pestana, nuevo)
  }

  function elegir(clave) {
    setPestana(clave)
    cargar(clave, filtro.desde && filtro.hasta ? filtro : undefined)
  }

  // Lo que se ve sigue al reporte que respondio el servidor, no a la pestana que se acaba de pulsar
  const Gestion = datos ? GESTION[datos.clave] : null

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.reportes')} titulo={t('admin.reportes.titulo')}>
      <Pestanas
        etiqueta={t('admin.reportes.titulo')}
        valor={pestana}
        onCambiar={elegir}
        opciones={PESTANAS.map((p) => ({ valor: p, etiqueta: t(seAgrupa(p) ? `admin.reportes.metrica.${p}.pestana` : `admin.reportes.${p}.pestana`) }))}
      />
      <div className="reportes__filtros">
        <Campo etiqueta={t('admin.reportes.desde')} type="date" value={filtro.desde} max={filtro.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('admin.reportes.hasta')} type="date" value={filtro.hasta} min={filtro.desde || undefined} onChange={cambiar('hasta')} />
        {seAgrupa(pestana) && (
          <Campo
            etiqueta={t('admin.reportes.agrupacion')}
            tipo="selector"
            value={filtro.agrupacion}
            onChange={cambiar('agrupacion')}
            opciones={AGRUPACIONES.map((a) => ({ valor: a, etiqueta: t(`admin.reportes.por.${a}`) }))}
          />
        )}
      </div>
      {error && <Alerta tono="error" titulo={t('admin.reportes.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {cargando && !datos && <Cargando />}
      {Gestion && <Gestion datos={datos} />}
      {datos && !Gestion && <Resultado datos={datos} />}
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
                <Barra partes={[Number(p.b2c), Number(p.b2b)]} mayor={mayor} />
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
