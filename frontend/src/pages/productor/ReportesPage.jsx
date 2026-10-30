import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlmacen from '../../assets/icons/almacen.svg'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoMerma from '../../assets/icons/merma.svg'
import iconoMovimientos from '../../assets/icons/movimientos.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import { consultarReporteProductor, urlReporteProductor } from '../../api/productor.js'
import { opcion, useReporte } from '../../api/useReporte.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DescargasReporte from '../../components/DescargasReporte.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import { capital, fecha, mesAnio, precio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './ReportesPage.css'

/** Pestanas de la pantalla, en orden. Cada una es /api/productor/reportes/{clave}. */
const METRICAS = ['ventas', 'produccion', 'inventario', 'consumo']

const AGRUPACIONES = ['DIA', 'SEMANA', 'MES']

const MODALIDADES = ['MINORISTA', 'MAYORISTA']

/** Filtros que solo tienen sentido en algunas pestanas: la modalidad de compra es de las ventas y el consumo. */
const CON_MODALIDAD = ['ventas', 'consumo']

/** Formatos de exportacion: el reporte completo de lo que se esta viendo. */
const FORMATOS = ['pdf', 'xlsx']

const FILTRO_VACIO = { desde: '', hasta: '', agrupacion: '', producto: '', modalidad: '' }

/** La modalidad de compra solo viaja a las pestañas que la usan. */
const consultarConModalidad = (clave, valores) =>
  consultarReporteProductor(clave, valores && { ...valores, modalidad: CON_MODALIDAD.includes(clave) ? valores.modalidad : '' })

/** El rango, la agrupacion y el producto que aplico el servidor; la modalidad, la que se envio. */
const aFiltro = (r, f) => ({ ...f, desde: r.desde, hasta: r.hasta, agrupacion: r.agrupacion, producto: opcion(r.producto) })

/**
 * Reportes del productor: estadisticas de sus productos por periodo, con el formato de los reportes de la
 * plataforma (SA-05). CCPIP-114 (RF-25): ventas; CCPIP-333 (RF-223): produccion;
 * CCPIP-334 (RF-224): inventario; CCPIP-135 (RF-105): consumo por modalidad y frecuencia de compra;
 * CCPIP-115 (RF-26) y CCPIP-335 (RF-225): exportacion en PDF y Excel. El servidor solo envia sus productos (RNF-05) y ningun
 * dato de los compradores; la pantalla solo muestra lo calculado.
 */
export default function ReportesPage({ path }) {
  const { t } = useTranslation()
  const [metrica, setMetrica] = useState(METRICAS[0])
  const { filtro, datos, error, cargando, vigente, cargar: consultar, aplicar } =
    useReporte(consultarConModalidad, aFiltro, METRICAS[0], FILTRO_VACIO)

  const cambiar = (campo) => (e) => aplicar(metrica, { ...filtro, [campo]: e.target.value })

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
        {datos && (
          <DescargasReporte className="reportes-productor__exportar" formatos={FORMATOS} disponible={vigente} cargando={cargando}
            etiqueta={(f) => t(`productor.reportes.exportar.${f}`)}
            url={(f, idioma) => urlReporteProductor(f, datos.aplicado, idioma)} />
        )}
      </div>
      {error && <Alerta tono="error" titulo={t('productor.reportes.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {cargando && !datos && <Cargando />}
      {mostrada === 'ventas' && <Ventas datos={datos} />}
      {mostrada === 'produccion' && <Produccion datos={datos} />}
      {mostrada === 'inventario' && <Inventario datos={datos} />}
      {mostrada === 'consumo' && <Consumo datos={datos} />}
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
  const filtroKey = JSON.stringify([datos.aplicado?.desde, datos.aplicado?.hasta, datos.aplicado?.agrupacion, datos.aplicado?.producto])
  const paginasPeriodo = usePaginacionLocal(periodos, filtroKey)
  const paginasProducto = usePaginacionLocal(porProducto, filtroKey)
  const periodosVisibles = paginasPeriodo.filas
  const productosVisibles = paginasProducto.filas
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
          {periodosVisibles.map((p) => (
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
        <Paginacion {...paginasPeriodo} />
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
            {productosVisibles.map((p) => (
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
        <Paginacion {...paginasProducto} />
      </section>
    </>
  )
}

/** CCPIP-333: lo que ingreso a su inventario por periodo (lotes nuevos y entradas de produccion), y por producto. */
function Produccion({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const filtroKey = JSON.stringify([datos.aplicado?.desde, datos.aplicado?.hasta, datos.aplicado?.agrupacion, datos.aplicado?.producto])
  const paginasPeriodo = usePaginacionLocal(periodos, filtroKey)
  const paginasProducto = usePaginacionLocal(porProducto, filtroKey)
  const periodosVisibles = paginasPeriodo.filas
  const productosVisibles = paginasProducto.filas
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
          {periodosVisibles.map((p) => (
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
        <Paginacion {...paginasPeriodo} />
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
            {productosVisibles.map((p) => (
              <div key={p.productoId} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex label-m" role="cell">{p.producto}</span>
                <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.unidades)}</span>
                <span className="tabla__celda reportes-productor__num" role="cell">{unidades(p.lotes)}</span>
              </div>
            ))}
          </div>
        )}
        <Paginacion {...paginasProducto} />
      </section>
    </>
  )
}

/** Columnas de cifras del inventario, en el orden de la tabla. */
const COLUMNAS_INVENTARIO = ['saldoInicial', 'entradas', 'vendidasB2c', 'vendidasB2b', 'merma', 'otrasSalidas', 'saldoFinal']
const COLUMNAS_INVENTARIO_PRODUCTO = ['saldoInicial', 'entradas', 'vendidas', 'merma', 'otrasSalidas', 'saldoFinal']

/**
 * CCPIP-334: cuanto tuvo de sus productos y por que cambio, periodo a periodo: cada saldo final es el
 * inicial mas las entradas menos lo vendido, la merma y otras salidas.
 */
function Inventario({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const filtroKey = JSON.stringify([datos.aplicado?.desde, datos.aplicado?.hasta, datos.aplicado?.agrupacion, datos.aplicado?.producto])
  const paginasPeriodo = usePaginacionLocal(periodos, filtroKey)
  const paginasProducto = usePaginacionLocal(porProducto, filtroKey)
  const periodosVisibles = paginasPeriodo.filas
  const productosVisibles = paginasProducto.filas
  const salidas = [totales.vendidasB2c, totales.vendidasB2b, totales.merma, totales.otrasSalidas].reduce((a, b) => a + Number(b), 0)
  const und = (v) => t('comun.und', { cantidad: unidades(v) })
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('productor.reportes.inventario.saldoInicial')} valor={und(totales.saldoInicial)} tono="neutro" icono={iconoAlmacen}
          detalle={t('productor.reportes.inventario.alInicio')} />
        <TarjetaKpi titulo={t('productor.reportes.inventario.entradas')} valor={und(totales.entradas)} tono="neutro" icono={iconoMovimientos}
          detalle={t('productor.reportes.inventario.entradasDetalle')} />
        <TarjetaKpi titulo={t('productor.reportes.inventario.salidas')} valor={und(salidas)} tono="neutro" icono={iconoMerma}
          detalle={t('productor.reportes.inventario.salidasDetalle', { vendidas: unidades(Number(totales.vendidasB2c) + Number(totales.vendidasB2b)), merma: unidades(totales.merma) })} />
        <TarjetaKpi titulo={t('productor.reportes.inventario.saldoFinal')} valor={und(totales.saldoFinal)} tono="neutro" icono={iconoAlmacen}
          detalle={t('productor.reportes.inventario.alFinal')} />
      </div>
      <section className="productor__seccion" aria-labelledby="reportes-inventario-periodo">
        <h2 id="reportes-inventario-periodo" className="heading-h3">{t('productor.reportes.inventario.porPeriodo')}</h2>
        <div className="tabla" role="table" aria-labelledby="reportes-inventario-periodo">
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda reportes-productor__periodo" role="columnheader">{t('productor.reportes.col.periodo')}</span>
            {COLUMNAS_INVENTARIO.map((c) => (
              <span key={c} className="tabla__celda reportes-productor__num reportes-productor__num--estrecha" role="columnheader">{t(`productor.reportes.col.${c}`)}</span>
            ))}
          </div>
          {periodosVisibles.map((p) => (
            <div key={p.inicio} className="tabla__fila" role="row">
              <span className="tabla__celda reportes-productor__periodo label-m" role="cell">{nombrePeriodo(p, agrupacion, t)}</span>
              {COLUMNAS_INVENTARIO.map((c) => (
                <span key={c} className={`tabla__celda reportes-productor__num reportes-productor__num--estrecha${c === 'saldoFinal' ? ' label-m' : ''}`} role="cell">{unidades(p[c])}</span>
              ))}
            </div>
          ))}
        </div>
        <Paginacion {...paginasPeriodo} />
        <p className="caption">{t('productor.reportes.inventario.nota')}</p>
      </section>
      <section className="productor__seccion" aria-labelledby="reportes-inventario-producto">
        <h2 id="reportes-inventario-producto" className="heading-h3">{t('productor.reportes.inventario.porProducto')}</h2>
        {porProducto.length === 0 ? (
          <EstadoVacio titulo={t('productor.reportes.inventario.vacio')} descripcion={t('productor.reportes.vacioTexto')} />
        ) : (
          <div className="tabla" role="table" aria-labelledby="reportes-inventario-producto">
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.reportes.col.producto')}</span>
              {COLUMNAS_INVENTARIO_PRODUCTO.map((c) => (
                <span key={c} className="tabla__celda reportes-productor__num" role="columnheader">{t(`productor.reportes.col.${c}`)}</span>
              ))}
            </div>
            {productosVisibles.map((p) => (
              <div key={p.productoId} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex label-m" role="cell">{p.producto}</span>
                {COLUMNAS_INVENTARIO_PRODUCTO.map((c) => (
                  <span key={c} className={`tabla__celda reportes-productor__num${c === 'saldoFinal' ? ' label-m' : ''}`} role="cell">{unidades(p[c])}</span>
                ))}
              </div>
            ))}
          </div>
        )}
        <Paginacion {...paginasProducto} />
      </section>
    </>
  )
}

/** Columnas de conteos del consumo y de la frecuencia, en el orden de la tabla. */
const COLUMNAS_CONSUMO = ['pedidos', 'compradores', 'unidadesB2c', 'unidadesB2b']
const COLUMNAS_FRECUENCIA = ['compradoresB2c', 'compradoresB2b', 'compradores']

/**
 * CCPIP-135 (RF-105): cuanto le compran por modalidad (pedidos, compradores, volumen e importe) y con que
 * frecuencia. El servidor solo envia conteos: la pantalla nunca sabe quien compro.
 */
function Consumo({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, frecuencia, agrupacion } = datos
  const filtroKey = JSON.stringify([datos.aplicado?.desde, datos.aplicado?.hasta, datos.aplicado?.agrupacion, datos.aplicado?.producto, datos.aplicado?.modalidad])
  const paginasPeriodo = usePaginacionLocal(periodos, filtroKey)
  const mayor = Math.max(...periodos.map((p) => Number(p.importeB2c) + Number(p.importeB2b)), 0)
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('productor.reportes.consumo.pedidos')} valor={unidades(totales.pedidos)} tono="neutro" icono={iconoGrafico}
          detalle={t('productor.reportes.consumo.pedidosDetalle', { compradores: unidades(totales.compradores) })} />
        <TarjetaKpi titulo={t('productor.reportes.b2c')} valor={precio(totales.importeB2c)} tono="neutro" icono={iconoTienda}
          detalle={t('productor.reportes.ventas.unidades', { cantidad: unidades(totales.unidadesB2c) })} />
        <TarjetaKpi titulo={t('productor.reportes.b2b')} valor={precio(totales.importeB2b)} tono="neutro" icono={iconoComunidad}
          detalle={t('productor.reportes.ventas.unidades', { cantidad: unidades(totales.unidadesB2b) })} />
        <TarjetaKpi titulo={t('productor.reportes.consumo.ticket')} valor={precio(totales.ticketPromedio)} tono="neutro" icono={iconoCaja}
          detalle={t('productor.reportes.consumo.ticketDetalle')} />
        <TarjetaKpi titulo={t('productor.reportes.consumo.comprasPorComprador')} valor={unidades(totales.comprasPorComprador)} tono="neutro"
          icono={iconoMovimientos} detalle={t('productor.reportes.consumo.comprasPorCompradorDetalle')} />
      </div>
      <section className="productor__seccion" aria-labelledby="reportes-consumo-periodo">
        <h2 id="reportes-consumo-periodo" className="heading-h3">{t('productor.reportes.consumo.porPeriodo')}</h2>
        <div className="tabla" role="table" aria-labelledby="reportes-consumo-periodo">
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda reportes-productor__periodo" role="columnheader">{t('productor.reportes.col.periodo')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.reportes.col.barraVentas')}</span>
            {COLUMNAS_CONSUMO.map((c) => (
              <span key={c} className="tabla__celda reportes-productor__num reportes-productor__num--estrecha" role="columnheader">{t(`productor.reportes.col.${c}`)}</span>
            ))}
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.b2c')}</span>
            <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.b2b')}</span>
          </div>
          {paginasPeriodo.filas.map((p) => (
            <div key={p.inicio} className="tabla__fila" role="row">
              <span className="tabla__celda reportes-productor__periodo label-m" role="cell">{nombrePeriodo(p, agrupacion, t)}</span>
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <Barra partes={[Number(p.importeB2c), Number(p.importeB2b)]} mayor={mayor} />
              </span>
              {COLUMNAS_CONSUMO.map((c) => (
                <span key={c} className="tabla__celda reportes-productor__num reportes-productor__num--estrecha" role="cell">{unidades(p[c])}</span>
              ))}
              <span className="tabla__celda reportes-productor__num" role="cell">{precio(p.importeB2c)}</span>
              <span className="tabla__celda reportes-productor__num" role="cell">{precio(p.importeB2b)}</span>
            </div>
          ))}
        </div>
        <Paginacion {...paginasPeriodo} />
        <p className="caption">{t('productor.reportes.consumo.nota')}</p>
      </section>
      <section className="productor__seccion" aria-labelledby="reportes-consumo-frecuencia">
        <h2 id="reportes-consumo-frecuencia" className="heading-h3">{t('productor.reportes.consumo.frecuencia')}</h2>
        {totales.compradores === 0 ? (
          <EstadoVacio titulo={t('productor.reportes.consumo.vacio')} descripcion={t('productor.reportes.vacioTexto')} />
        ) : (
          <div className="tabla" role="table" aria-labelledby="reportes-consumo-frecuencia">
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.reportes.col.compras')}</span>
              {COLUMNAS_FRECUENCIA.map((c) => (
                <span key={c} className="tabla__celda reportes-productor__num" role="columnheader">{t(`productor.reportes.col.${c}`)}</span>
              ))}
              <span className="tabla__celda reportes-productor__num" role="columnheader">{t('productor.reportes.col.importe')}</span>
            </div>
            {frecuencia.map((f) => (
              <div key={f.tramo} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex label-m" role="cell">{t(`productor.reportes.consumo.tramos.${f.tramo}`)}</span>
                {COLUMNAS_FRECUENCIA.map((c) => (
                  <span key={c} className={`tabla__celda reportes-productor__num${c === 'compradores' ? ' label-m' : ''}`} role="cell">{unidades(f[c])}</span>
                ))}
                <span className="tabla__celda reportes-productor__num" role="cell">{precio(f.importe)}</span>
              </div>
            ))}
          </div>
        )}
        <p className="caption">{t('productor.reportes.consumo.notaFrecuencia')}</p>
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
