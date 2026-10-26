import { useTranslation } from 'react-i18next'
import iconoMovimientos from '../../../assets/icons/movimientos.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import { unidades } from '../../../formato.js'
import { Barra, columnas, Kpis, pendiente, Tablas } from './comun.jsx'

/**
 * CCPIP-117 (RF-65): lo que entro a sus puntos por periodo, recibido directamente o por transferencia. Como
 * no atendido, las transferencias hacia sus puntos que aun no llegan.
 */
export default function Entradas({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos } = datos
  const col = columnas(t, datos.agrupacion)
  const mayor = Math.max(...periodos.map((p) => Number(p.total)), 0)
  const pendientes = totales.transferenciasPendientes
  const und = (v) => t('comun.und', { cantidad: unidades(v) })
  return (
    <>
      <Kpis textos="acopio.reportes.entradas" tarjetas={[
        { clave: 'total', valor: und(totales.total), icono: iconoMovimientos,
          vars: { recepcion: unidades(totales.recepcion), transferencias: unidades(totales.transferencias) } },
        { clave: 'porLlegar', valor: und(totales.unidadesPendientes), icono: iconoReloj, tono: pendiente(pendientes),
          vars: { count: pendientes } },
      ]} />
      <Tablas id="entradas" textos="acopio.reportes.entradas" datos={datos}
        porPeriodo={[
          col.periodo,
          { clave: 'barra', etiqueta: t('acopio.reportes.col.barraEntradas'), barra: true,
            valor: (p) => <Barra partes={[Number(p.recepcion), Number(p.transferencias)]} mayor={mayor} /> },
          col.cantidad('recepcion'),
          col.cantidad('transferencias'),
          col.cantidad('total'),
          col.cantidad('acumulado', { fuerte: true }),
        ]}
        porProducto={[...col.producto, col.cantidad('recepcion'), col.cantidad('transferencias'), col.cantidad('total', { fuerte: true })]} />
    </>
  )
}
