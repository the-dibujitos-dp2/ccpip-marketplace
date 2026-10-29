import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import { consultarReporte, urlReporteGestion } from '../../api/admin.js'
import { useReporte } from '../../api/useReporte.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DescargasReporte from '../../components/DescargasReporte.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import { Barra, Kpis, nombrePeriodo } from '../../components/TablaReporte.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { precio } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'
import './ReportesPage.css'
import Calificaciones from './reportes/Calificaciones.jsx'
import { rango } from './reportes/comun.jsx'
import Indicadores from './reportes/Indicadores.jsx'
import OrigenDestino from './reportes/OrigenDestino.jsx'
import Productores from './reportes/Productores.jsx'
import Puntos from './reportes/Puntos.jsx'

/** Metricas de SA-05, en el orden de las pestanas. Cada una es un endpoint /api/admin/reportes/{clave}. */
const METRICAS = ['ventas', 'comisiones', 'ingresos']

/** Reportes de gestion (SA-10), despues de las metricas: cada uno es /api/admin/reportes/{clave}. */
const GESTION = { indicadores: Indicadores, productores: Productores, puntos: Puntos, calificaciones: Calificaciones, 'origen-destino': OrigenDestino }

const PESTANAS = [...METRICAS, ...Object.keys(GESTION)]

const AGRUPACIONES = ['DIA', 'SEMANA', 'MES']

/** Formatos de exportacion de un reporte de gestion: la pestaña que se ve, con sus filtros. */
const FORMATOS = ['pdf', 'xlsx']

const FILTRO_VACIO = { desde: '', hasta: '', agrupacion: '', linea: '' }

/** Los filtros que aplico el servidor; un reporte sin agrupacion (o sin linea) conserva la elegida. */
const aFiltro = (r, anterior) => ({
  desde: r.desde,
  hasta: r.hasta,
  agrupacion: r.agrupacion ?? anterior.agrupacion,
  linea: 'linea' in r ? (r.linea ?? '') : anterior.linea,
})

const esMetrica = (clave) => METRICAS.includes(clave)

/** Las metricas y los indicadores tienen su serie por periodo: se agrupan. */
const seAgrupa = (clave) => esMetrica(clave) || clave === 'indicadores'

/**
 * SA-05 y SA-10 Reportes de la plataforma (superadministrador). Solo cifras agregadas: el servidor no envia
 * compradores (SA-08).
 * - CCPIP-345 (RF-235), CCPIP-346 (RF-236) y CCPIP-93 (RF-71): ventas, comisiones e ingresos acumulados por
 *   periodo con su desglose B2C (tarifa minorista) y B2B (tarifa mayorista)
 * - CCPIP-348 (RF-238), CCPIP-349 (RF-239), CCPIP-350 (RF-240) y CCPIP-94 (RF-81): indicadores de ventas,
 *   productores, productos y actividad frente al periodo anterior
 * - CCPIP-122 (RF-86): productores que mas venden
 * - CCPIP-355 (RF-245) y CCPIP-356 (RF-246): ventas y denuncias por punto de acopio
 * - CCPIP-123 (RF-87) y CCPIP-352 (RF-242): calificaciones de productos y productores, por linea
 * - CCPIP-80 (RF-66): origen y destino de las ventas
 * - Los reportes de gestion se descargan en PDF o Excel: la pestaña que se ve, con sus filtros
 */
export default function ReportesPage({ path }) {
  const { t } = useTranslation()
  const [pestana, setPestana] = useState(PESTANAS[0])
  const { filtro, datos, error, cargando, vigente, cargar, aplicar } =
    useReporte(consultarReporte, aFiltro, PESTANAS[0], FILTRO_VACIO)

  const cambiar = (campo) => (e) => aplicar(pestana, { ...filtro, [campo]: e.target.value })

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
        opciones={PESTANAS.map((p) => ({ valor: p, etiqueta: t(esMetrica(p) ? `admin.reportes.metrica.${p}.pestana` : `admin.reportes.${p}.pestana`) }))}
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
        {pestana === 'calificaciones' && (
          <Campo
            etiqueta={t('admin.reportes.linea')}
            tipo="selector"
            placeholder={t('admin.reportes.todas')}
            value={filtro.linea}
            onChange={cambiar('linea')}
            opciones={(datos?.lineas ?? []).map((l) => ({ valor: l.codigo, etiqueta: l.nombre }))}
          />
        )}
        {Gestion && (
          <DescargasReporte className="reportes__exportar" formatos={FORMATOS} disponible={vigente} cargando={cargando}
            etiqueta={(f) => t(`admin.reportes.exportar.${f}`)} url={(f, idioma) => urlReporteGestion(datos.clave, f, datos.aplicado, idioma)} />
        )}
      </div>
      {error && <Alerta tono="error" titulo={t('admin.reportes.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {cargando && !datos && <Cargando />}
      {Gestion && <Gestion datos={datos} />}
      {datos && !Gestion && <Resultado datos={datos} />}
    </LayoutAdmin>
  )
}

function Resultado({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, agrupacion } = datos
  // Los textos siguen a la metrica que respondio el servidor, no a la pestana que se acaba de pulsar
  const metrica = `metrica.${datos.metrica.toLowerCase()}`
  const clave = `admin.reportes.${metrica}`
  const mayor = Math.max(...periodos.map((p) => Number(p.total)), 0)
  const paginacion = usePaginacionLocal(periodos, JSON.stringify([datos.desde, datos.hasta, agrupacion, datos.metrica]))
  return (
    <>
      <Kpis textos="admin.reportes" tarjetas={[
        { clave: `${metrica}.total`, valor: precio(totales.total), icono: iconoGrafico, detalle: 'rango', vars: rango(datos) },
        { clave: 'b2c', valor: precio(totales.b2c), icono: iconoTienda },
        { clave: 'b2b', valor: precio(totales.b2b), icono: iconoComunidad },
      ]} />
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
          {paginacion.filas.map((p) => (
            <div key={p.inicio} className="tabla__fila" role="row">
              <span className="tabla__celda reportes__periodo label-m" role="cell">{nombrePeriodo(p, agrupacion, t, 'admin.reportes.semana')}</span>
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
        <Paginacion {...paginacion} />
        <p className="caption">{t(`${clave}.nota`)}</p>
      </section>
    </>
  )
}
