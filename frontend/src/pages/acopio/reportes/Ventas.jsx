import { useTranslation } from 'react-i18next'
import iconoCaja from '../../../assets/icons/caja.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import { precio, unidades } from '../../../formato.js'
import { columnas, Kpis, pendiente, Tablas } from './comun.jsx'

/**
 * CCPIP-342 (RF-232): lo vendido desde sus puntos por periodo: pedidos atendidos (entregados) y por
 * entregar (pagados), unidades e importes B2C (minorista) y B2B (mayorista). Sin datos de los compradores.
 */
export default function Ventas({ datos }) {
  const { t } = useTranslation()
  const { totales } = datos
  const col = columnas(t, datos.agrupacion)
  const resumen = { unidades: unidades(totales.unidades), b2c: precio(totales.b2c), b2b: precio(totales.b2b) }
  return (
    <>
      <Kpis textos="acopio.reportes.ventas" tarjetas={[
        { clave: 'total', valor: precio(totales.total), icono: iconoGrafico, vars: resumen },
        { clave: 'atendidos', valor: unidades(totales.pedidosAtendidos), icono: iconoCaja, tono: 'exito', vars: { count: totales.pedidos } },
        { clave: 'porEntregar', valor: unidades(totales.pedidosPorEntregar), icono: iconoReloj, tono: pendiente(totales.pedidosPorEntregar) },
      ]} />
      <Tablas id="ventas" textos="acopio.reportes.ventas" datos={datos} estrecha={{ periodo: true }}
        porPeriodo={[col.periodo, ...['pedidos', 'pedidosAtendidos', 'pedidosPorEntregar', 'unidades'].map((c) => col.cantidad(c)),
          ...['b2c', 'b2b', 'total'].map((c) => col.importe(c)), col.importe('acumulado', { fuerte: true })]}
        porProducto={[...col.producto, col.cantidad('unidades'), col.importe('total', { fuerte: true })]} />
    </>
  )
}
