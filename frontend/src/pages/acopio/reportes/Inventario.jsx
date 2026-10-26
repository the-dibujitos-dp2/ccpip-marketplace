import { useTranslation } from 'react-i18next'
import iconoAlmacen from '../../../assets/icons/almacen.svg'
import iconoMovimientos from '../../../assets/icons/movimientos.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import { unidades } from '../../../formato.js'
import { columnas, Kpis, pendiente, Tablas } from './comun.jsx'

const SALDOS = ['saldoInicial', 'entradas', 'salidas']

/**
 * CCPIP-343 (RF-233): cuanto hubo en sus puntos y por que cambio, periodo a periodo: cada saldo final es el
 * inicial mas las entradas menos las salidas. Como no atendido, lo vendido que sigue en el punto esperando
 * entrega: ya no esta en el saldo, pero si en el almacen.
 */
export default function Inventario({ datos }) {
  const { t } = useTranslation()
  const { totales } = datos
  const col = columnas(t, datos.agrupacion)
  const und = (v) => t('comun.und', { cantidad: unidades(v) })
  const variacion = { entradas: unidades(totales.entradas), salidas: unidades(totales.salidas) }
  return (
    <>
      <Kpis textos="acopio.reportes.inventario" tarjetas={[
        { clave: 'saldoInicial', valor: und(totales.saldoInicial), icono: iconoAlmacen, detalle: 'alInicio' },
        { clave: 'movimiento', valor: und(Number(totales.entradas) - Number(totales.salidas)), icono: iconoMovimientos, vars: variacion },
        { clave: 'saldoFinal', valor: und(totales.saldoFinal), icono: iconoAlmacen, detalle: 'alFinal' },
        { clave: 'porEntregar', valor: und(totales.porEntregar), icono: iconoReloj, tono: pendiente(totales.porEntregar),
          vars: { cantidad: und(totales.existenciaFisica) } },
      ]} />
      <Tablas id="inventario" textos="acopio.reportes.inventario" datos={datos} estrecha={{ periodo: true, producto: true }}
        porPeriodo={[col.periodo, ...SALDOS.map((c) => col.cantidad(c)), col.cantidad('saldoFinal', { fuerte: true })]}
        porProducto={[...col.producto, ...SALDOS.map((c) => col.cantidad(c)), col.cantidad('saldoFinal', { fuerte: true }),
          col.cantidad('ventasPorEntregar', { valor: (p) => unidades(p.porEntregar) })]} />
    </>
  )
}
