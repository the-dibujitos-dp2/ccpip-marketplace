import { useTranslation } from 'react-i18next'
import iconoCaja from '../../../assets/icons/caja.svg'
import iconoMovimientos from '../../../assets/icons/movimientos.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import { unidades } from '../../../formato.js'
import { SerieTemporal } from '../../../components/reportes-visuales/index.jsx'
import { columnas, Kpis, pendiente, Tablas } from './comun.jsx'

/**
 * CCPIP-341 (RF-231): lo que salio de sus puntos por periodo. Las ventas se separan en entregadas
 * (atendidas) y pagadas aun por entregar (no atendidas); aparte, transferencias, devoluciones y merma.
 */
export default function Salidas({ datos }) {
  const { t, i18n } = useTranslation()
  const titulo = (es, en) => (i18n.resolvedLanguage || '').startsWith('es') ? es : en
  const { totales } = datos
  const col = columnas(t, datos.agrupacion)
  const und = (v) => t('comun.und', { cantidad: unidades(v) })
  const detalle = { transferencias: unidades(totales.transferencias), devoluciones: unidades(totales.devoluciones), merma: unidades(totales.merma) }
  return (
    <>
      <SerieTemporal titulo={titulo('Evolución de salidas', 'Stock-out trend')} unidad="und" series={[
        ...['ventasEntregadas', 'ventasPorEntregar', 'transferencias', 'devoluciones', 'merma'].map((id) => ({
          id, nombre: t(`acopio.reportes.col.${id}`), unidad: 'und', puntos: datos.periodos.map((p) => ({ etiqueta: p.inicio, valor: p[id] })),
        })),
      ]} />
      <Kpis textos="acopio.reportes.salidas" tarjetas={[
        { clave: 'total', valor: und(totales.total), icono: iconoMovimientos, vars: detalle },
        { clave: 'entregadas', valor: und(totales.ventasEntregadas), icono: iconoCaja, tono: 'exito' },
        { clave: 'porEntregar', valor: und(totales.ventasPorEntregar), icono: iconoReloj, tono: pendiente(totales.ventasPorEntregar) },
      ]} />
      <Tablas id="salidas" textos="acopio.reportes.salidas" datos={datos} estrecha={{ periodo: true }}
        porPeriodo={[col.periodo, ...['ventasEntregadas', 'ventasPorEntregar', 'transferencias', 'devoluciones', 'merma', 'total']
          .map((c) => col.cantidad(c)), col.cantidad('acumulado', { fuerte: true })]}
        porProducto={[...col.producto, ...['ventas', 'transferencias', 'devoluciones', 'merma'].map((c) => col.cantidad(c)),
          col.cantidad('total', { fuerte: true })]} />
    </>
  )
}
