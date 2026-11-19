import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCaja from '../../../assets/icons/caja.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import { precio, unidades } from '../../../formato.js'
import { SerieTemporal } from '../../../components/reportes-visuales/index.jsx'
import { columnas, Kpis, pendiente, Tablas } from './comun.jsx'

/** CCPIP-342: importes y pedidos de sus puntos, con estados separados y sin datos de compradores. */
export default function Ventas({ datos }) {
  const { t } = useTranslation()
  const [vista, setVista] = useState('desglose')
  const [estado, setEstado] = useState('todos')
  const { totales, periodos } = datos
  const col = columnas(t, datos.agrupacion)
  const resumen = { unidades: unidades(totales.unidades), b2c: precio(totales.b2c), b2b: precio(totales.b2b) }
  const modos = [
    ['desglose', t('acopio.reportes.grafica.desglose')],
    ['total', t('acopio.reportes.col.total')],
    ['acumulado', t('acopio.reportes.col.acumulado')],
    ['pedidos', t('acopio.reportes.grafica.pedidos')],
  ]
  const estados = [
    ['todos', t('acopio.reportes.grafica.todosEstados')],
    ['pedidos', t('acopio.reportes.grafica.totalPedidos')],
    ['pedidosAtendidos', t('acopio.reportes.grafica.atendidos')],
    ['pedidosPorEntregar', t('acopio.reportes.grafica.porEntregar')],
  ]
  const campos = vista === 'pedidos'
    ? estado === 'todos' ? ['pedidos', 'pedidosAtendidos', 'pedidosPorEntregar'] : [estado]
    : vista === 'desglose' ? ['b2c', 'b2b'] : [vista]
  const unidad = vista === 'pedidos' ? '' : 'S/'
  const series = campos.map((id) => ({
    id, nombre: t(`acopio.reportes.col.${id}`), unidad,
    puntos: periodos.map((periodo) => ({ etiqueta: periodo.inicio, valor: periodo[id] })),
  }))
  const dataset = JSON.stringify([datos.clave, datos.aplicado, datos.desde, datos.hasta, datos.agrupacion, periodos.map((periodo) => periodo.inicio)])
  return (
    <>
      <div className="chips__lista acopio-visualizaciones__controles" role="group" aria-label={t('acopio.reportes.grafica.magnitud')}>
        {modos.map(([id, label]) => <button key={id} type="button" className={`chip${vista === id ? ' chip--activo' : ''}`} aria-pressed={vista === id} onClick={() => setVista(id)}>{label}</button>)}
      </div>
      {vista === 'pedidos' && <div className="chips__lista acopio-visualizaciones__controles" role="group" aria-label={t('acopio.reportes.grafica.estadoPedidos')}>
        {estados.map(([id, label]) => <button key={id} type="button" className={`chip${estado === id ? ' chip--activo' : ''}`} aria-pressed={estado === id} onClick={() => setEstado(id)}>{label}</button>)}
      </div>}
      <SerieTemporal key={`${dataset}-${vista}-${estado}`} titulo={vista === 'pedidos' ? t('acopio.reportes.grafica.evolucionPedidos') : t('acopio.reportes.grafica.evolucionVentas')} unidad={unidad} series={series} />
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
