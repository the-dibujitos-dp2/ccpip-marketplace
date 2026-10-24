import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import { consultarReporteProductor } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import { capital, fecha, mesAnio, precio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './ReportesPage.css'

/** Pestanas de la pantalla, en orden. Cada una es /api/productor/reportes/{clave}. */
const METRICAS = ['ventas', 'produccion']

const AGRUPACIONES = ['DIA', 'SEMANA', 'MES']

const MODALIDADES = ['MINORISTA', 'MAYORISTA']

/** Filtros que solo tienen sentido en algunas pestanas: la modalidad de compra es de las ventas. */
const CON_MODALIDAD = ['ventas']

/**
 * Reportes del productor: estadisticas de sus productos por periodo, con el formato de los reportes de la
 * plataforma (SA-05). CCPIP-114 (RF-25): ventas; CCPIP-333 (RF-223): produccion. El servidor solo envia sus productos (RNF-05) y ningun
 * dato de los compradores; la pantalla solo muestra lo calculado.
 */
export default function ReportesPage({ path }) {
  const { t } = useTranslation()
  const [metrica, setMetrica] = useState(METRICAS[0])
  const [filtro, setFiltro] = useState({ desde: '', hasta: '', agrupacion: '', producto: '', modalidad: '' })
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)

  const consultar = useCallback(async (clave, valores) => {
    setCargando(true)
    setError(null)
    try {
      const enviar = valores && { ...valores, modalidad: CON_MODALIDAD.includes(clave) ? valores.modalidad : '' }
      const respuesta = await consultarReporteProductor(clave, enviar)
      setDatos({ ...respuesta, clave })
      setFiltro((f) => ({
        ...f,
        desde: respuesta.desde,
        hasta: respuesta.hasta,
        agrupacion: respuesta.agrupacion,
        producto: respuesta.producto == null ? '' : String(respuesta.producto),
      }))
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
    if (nuevo.desde && nuevo.hasta) consultar(metrica, nuevo)
  }

  function elegirMetrica(clave) {
    setMetrica(clave)
    consultar(clave, filtro.desde && filtro.hasta ? filtro : undefined)
  }

  const productos = datos?.productos ?? []
  // Los textos siguen a la metrica que respondio el servidor, no a la pestana que se acaba de pulsar
  const mostrada = datos?.clave

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.reportes')} titulo={t('productor.reportes.titulo')}>
      {METRICAS.length > 1 && (
        <Pestanas
          etiqueta={t('productor.reportes.titulo')}
          valor={metrica}
          onCambiar={elegirMetrica}
          opciones={METRICAS.map((m) => ({ valor: m, etiqueta: t(`productor.reportes.${m}.pestana`) }))}
        />
      )}
      <div className="reportes-productor__filtros">
        <Campo etiqueta={t('productor.reportes.desde')} type="date" value={filtro.desde} max={filtro.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('productor.reportes.hasta')} type="date" value={filtro.hasta} min={filtro.desde || undefined} onChange={cambiar('hasta')} />
        <Campo
          etiqueta={t('productor.reportes.agrupacion')}
          tipo="selector"
          value={filtro.agrupacion}
          onChange={cambiar('agrupacion')}
          opciones={AGRUPACIONES.map((a) => ({ valor: a, etiqueta: t(`productor.reportes.por.${a}`) }))}
        />
        <Campo
          etiqueta={t('productor.reportes.producto')}
          className="reportes-productor__selector-ancho"
          tipo="selector"
          placeholder={t('productor.reportes.todos')}
          value={filtro.producto}
          onChange={cambiar('producto')}
          opciones={productos.map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
        />
        {CON_MODALIDAD.includes(metrica) && (
          <Campo
            etiqueta={t('productor.reportes.modalidad')}
            className="reportes-productor__selector-ancho"
            tipo="selector"
            placeholder={t('productor.reportes.ambas')}
            value={filtro.modalidad}
            onChange={cambiar('modalidad')}
            opciones={MODALIDADES.map((m) => ({ valor: m, etiqueta: t(`productor.reportes.modalidades.${m}`) }))}
          />
        )}
      </div>
      {error && <Alerta tono="error" titulo={t('productor.reportes.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {cargando && !datos && <p className="caption">{t('comun.cargando')}</p>}
      {mostrada === 'ventas' && <Ventas datos={datos} />}
      {mostrada === 'produccion' && <Produccion datos={datos} />}
    </PaginaProductor>
  )
}

/** Nombre del periodo: el mes, la semana desde su lunes o el dia. */
function nombrePeriodo(p, agrupacion, t) {
  if (agrupacion === 'MES') return capital(mesAnio(p.inicio))
  if (agrupacion === 'SEMANA') return t('productor.reportes.semana', { inicio: fecha(p.inicio), fin: fecha(p.fin) })
  return fecha(p.inicio)
}

/** CCPIP-114: lo vendido por periodo, con su desglose B2C y B2B, y por producto. */
function Ventas({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const mayor = Math.max(...periodos.map((p) => Number(p.total)), 0)
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('productor.reportes.ventas.total')} valor={precio(totales.total)} tono="neutro" icono={iconoGrafico}
          detalle={t('productor.reportes.ventas.unidades', { cantidad: unidades(totales.unidades) })} />
        <TarjetaKpi titulo={t('productor.reportes.b2c')} valor={precio(totales.b2c)} icono={iconoTienda} detalle={t('productor.reportes.b2cDetalle')} tono="neutro" />
        <TarjetaKpi titulo={t('productor.reportes.b2b')} valor={precio(totales.b2b)} icono={iconoComunidad} detalle={t('productor.reportes.b2bDetalle')} tono="neutro" />
      </div>
      <section className="productor__seccion" aria-labelledby="reportes-ventas-periodo">
        <h2 id="reportes-ventas-periodo" className="heading-h3">{t('productor.reportes.ventas.porPeriodo')}</h2>
        <div className="tabla" role="table" aria-labelledby="reportes-ventas-periodo">
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda reportes-productor__periodo" role="columnheader">{t('productor.reportes.col.periodo')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.reportes.col.barraVentas')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.unidades')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.b2c')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.b2b')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.total')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.acumulado')}</span>
          </div>
          {periodos.map((p) => (
            <div key={p.inicio} className="tabla__fila" role="row">
              <span className="tabla__celda reportes-productor__periodo label-m" role="cell">{nombrePeriodo(p, agrupacion, t)}</span>
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <Barra partes={[Number(p.b2c), Number(p.b2b)]} mayor={mayor} />
              </span>
              <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.unidades)}</span>
              <span className="tabla__celda reportes-productor__num" role="cell">{precio(p.b2c)}</span>
              <span className="tabla__celda reportes-productor__num" role="cell">{precio(p.b2b)}</span>
              <span className="tabla__celda reportes-productor__num" role="cell">{precio(p.total)}</span>
              <span className="tabla__celda reportes-productor__num label-m" role="cell">{precio(p.acumulado)}</span>
            </div>
          ))}
        </div>
        <p className="caption">{t('productor.reportes.ventas.nota')}</p>
      </section>
      <section className="productor__seccion" aria-labelledby="reportes-ventas-producto">
        <h2 id="reportes-ventas-producto" className="heading-h3">{t('productor.reportes.ventas.porProducto')}</h2>
        {porProducto.length === 0 ? (
          <EstadoVacio titulo={t('productor.reportes.ventas.vacio')} descripcion={t('productor.reportes.vacioTexto')} />
        ) : (
          <div className="tabla" role="table" aria-labelledby="reportes-ventas-producto">
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.reportes.col.producto')}</span>
              <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.unidades')}</span>
              <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.b2c')}</span>
              <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.b2b')}</span>
              <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.total')}</span>
            </div>
            {porProducto.map((p) => (
              <div key={p.productoId} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex label-m" role="cell">{p.producto}</span>
                <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.unidades)}</span>
                <span className="tabla__celda reportes-productor__num" role="cell">{precio(p.b2c)}</span>
                <span className="tabla__celda reportes-productor__num" role="cell">{precio(p.b2b)}</span>
                <span className="tabla__celda reportes-productor__num label-m" role="cell">{precio(p.total)}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </>
  )
}

/** CCPIP-333: lo que ingreso a su inventario por periodo (lotes nuevos y entradas de produccion), y por producto. */
function Produccion({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const mayor = Math.max(...periodos.map((p) => Number(p.unidades)), 0)
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('productor.reportes.produccion.unidades')} valor={t('comun.und', { cantidad: unidades(totales.unidades) })}
          tono="neutro" icono={iconoGrafico} detalle={t('productor.reportes.produccion.unidadesDetalle')} />
        <TarjetaKpi titulo={t('productor.reportes.produccion.lotes')} valor={unidades(totales.lotes)} tono="neutro" icono={iconoCaja}
          detalle={t('productor.reportes.produccion.lotesDetalle')} />
      </div>
      <section className="productor__seccion" aria-labelledby="reportes-produccion-periodo">
        <h2 id="reportes-produccion-periodo" className="heading-h3">{t('productor.reportes.produccion.porPeriodo')}</h2>
        <div className="tabla" role="table" aria-labelledby="reportes-produccion-periodo">
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda reportes-productor__periodo" role="columnheader">{t('productor.reportes.col.periodo')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.reportes.col.barraProduccion')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.ingresadas')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.lotes')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.acumulado')}</span>
          </div>
          {periodos.map((p) => (
            <div key={p.inicio} className="tabla__fila" role="row">
              <span className="tabla__celda reportes-productor__periodo label-m" role="cell">{nombrePeriodo(p, agrupacion, t)}</span>
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <Barra partes={[Number(p.unidades)]} mayor={mayor} />
              </span>
              <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.unidades)}</span>
              <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.lotes)}</span>
              <span className="tabla__celda reportes-productor__num label-m" role="cell">{unidades(p.acumulado)}</span>
            </div>
          ))}
        </div>
        <p className="caption">{t('productor.reportes.produccion.nota')}</p>
      </section>
      <section className="productor__seccion" aria-labelledby="reportes-produccion-producto">
        <h2 id="reportes-produccion-producto" className="heading-h3">{t('productor.reportes.produccion.porProducto')}</h2>
        {porProducto.length === 0 ? (
          <EstadoVacio titulo={t('productor.reportes.produccion.vacio')} descripcion={t('productor.reportes.vacioTexto')} />
        ) : (
          <div className="tabla" role="table" aria-labelledby="reportes-produccion-producto">
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.reportes.col.producto')}</span>
              <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.ingresadas')}</span>
              <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.lotes')}</span>
            </div>
            {porProducto.map((p) => (
              <div key={p.productoId} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex label-m" role="cell">{p.producto}</span>
                <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.unidades)}</span>
                <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.lotes)}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </>
  )
}

/**
 * Barra apilada del periodo, a escala del periodo mayor: marca y acento, como SA-05. Decorativa: las
 * cifras estan en la fila.
 */
function Barra({ partes, mayor }) {
  if (!mayor) return null
  return (
    <span className="reportes-productor__barra" aria-hidden="true">
      {partes.map((valor, i) => (
        <span key={i} className={`reportes-productor__barra-${i}`} style={{ width: `${(valor / mayor) * 100}%` }} />
      ))}
    </span>
  )
}
