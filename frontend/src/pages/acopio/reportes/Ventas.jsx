import { useTranslation } from 'react-i18next'
import iconoCaja from '../../../assets/icons/caja.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import TarjetaKpi from '../../../components/TarjetaKpi.jsx'
import { precio, unidades } from '../../../formato.js'
import { nombrePeriodo, TablaReporte } from './comun.jsx'

/**
 * CCPIP-342 (RF-232): lo vendido desde sus puntos por periodo: pedidos atendidos (entregados) y por
 * entregar (pagados), unidades e importes B2C (minorista) y B2B (mayorista). Sin datos de los compradores.
 */
export default function Ventas({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const cantidad = (clave, opciones = {}) => ({ clave, etiqueta: t(`acopio.reportes.col.${clave}`), valor: (p) => unidades(p[clave]), ...opciones })
  const importe = (clave, opciones = {}) => ({ clave, etiqueta: t(`acopio.reportes.col.${clave}`), valor: (p) => precio(p[clave]), ...opciones })
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('acopio.reportes.ventas.total')} valor={precio(totales.total)} tono="neutro" icono={iconoGrafico}
          detalle={t('acopio.reportes.ventas.totalDetalle', { unidades: unidades(totales.unidades), b2c: precio(totales.b2c), b2b: precio(totales.b2b) })} />
        <TarjetaKpi titulo={t('acopio.reportes.ventas.atendidos')} valor={unidades(totales.pedidosAtendidos)} icono={iconoCaja}
          detalle={t('acopio.reportes.ventas.atendidosDetalle', { count: totales.pedidos })} />
        <TarjetaKpi titulo={t('acopio.reportes.ventas.porEntregar')} valor={unidades(totales.pedidosPorEntregar)} icono={iconoReloj}
          tono={totales.pedidosPorEntregar > 0 ? 'aviso' : 'neutro'} detalle={t('acopio.reportes.ventas.porEntregarDetalle')} />
      </div>
      <TablaReporte
        id="reportes-ventas-periodo"
        titulo={t('acopio.reportes.ventas.porPeriodo')}
        clave={(p) => p.inicio}
        filas={periodos}
        estrecha
        columnas={[
          { clave: 'periodo', etiqueta: t('acopio.reportes.col.periodo'), periodo: true, valor: (p) => nombrePeriodo(p, agrupacion, t) },
          cantidad('pedidos'),
          cantidad('pedidosAtendidos'),
          cantidad('pedidosPorEntregar'),
          cantidad('unidades'),
          importe('b2c'),
          importe('b2b'),
          importe('total'),
          importe('acumulado', { fuerte: true }),
        ]}
        nota={t('acopio.reportes.ventas.nota')}
      />
      <TablaReporte
        id="reportes-ventas-producto"
        titulo={t('acopio.reportes.ventas.porProducto')}
        clave={(p) => p.productoId}
        filas={porProducto}
        vacio={t('acopio.reportes.ventas.vacio')}
        columnas={[
          { clave: 'producto', etiqueta: t('acopio.reportes.col.producto'), texto: true, fuerte: true, valor: (p) => p.producto },
          { clave: 'productor', etiqueta: t('acopio.reportes.col.productor'), texto: true, valor: (p) => p.productor },
          cantidad('unidades'),
          importe('total', { fuerte: true }),
        ]}
      />
    </>
  )
}
