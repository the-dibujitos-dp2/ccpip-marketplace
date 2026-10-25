import { useTranslation } from 'react-i18next'
import iconoMovimientos from '../../../assets/icons/movimientos.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import TarjetaKpi from '../../../components/TarjetaKpi.jsx'
import { unidades } from '../../../formato.js'
import { Barra, nombrePeriodo, TablaReporte } from './comun.jsx'

/**
 * CCPIP-117 (RF-65): lo que entro a sus puntos por periodo, recibido directamente o por transferencia. Como
 * no atendido, las transferencias hacia sus puntos que aun no llegan.
 */
export default function Entradas({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const mayor = Math.max(...periodos.map((p) => Number(p.total)), 0)
  const und = (v) => t('comun.und', { cantidad: unidades(v) })
  const pendientes = totales.transferenciasPendientes
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('acopio.reportes.entradas.total')} valor={und(totales.total)} tono="neutro" icono={iconoMovimientos}
          detalle={t('acopio.reportes.entradas.totalDetalle', { recepcion: unidades(totales.recepcion), transferencias: unidades(totales.transferencias) })} />
        <TarjetaKpi titulo={t('acopio.reportes.entradas.porLlegar')} valor={und(totales.unidadesPendientes)} icono={iconoReloj}
          tono={pendientes > 0 ? 'aviso' : 'neutro'} detalle={t('acopio.reportes.entradas.porLlegarDetalle', { count: pendientes })} />
      </div>
      <TablaReporte
        id="reportes-entradas-periodo"
        titulo={t('acopio.reportes.entradas.porPeriodo')}
        clave={(p) => p.inicio}
        filas={periodos}
        columnas={[
          { clave: 'periodo', etiqueta: t('acopio.reportes.col.periodo'), periodo: true, valor: (p) => nombrePeriodo(p, agrupacion, t) },
          { clave: 'barra', etiqueta: t('acopio.reportes.col.barraEntradas'), barra: true,
            valor: (p) => <Barra partes={[Number(p.recepcion), Number(p.transferencias)]} mayor={mayor} /> },
          { clave: 'recepcion', etiqueta: t('acopio.reportes.col.recepcion'), valor: (p) => unidades(p.recepcion) },
          { clave: 'transferencias', etiqueta: t('acopio.reportes.col.transferencias'), valor: (p) => unidades(p.transferencias) },
          { clave: 'total', etiqueta: t('acopio.reportes.col.total'), valor: (p) => unidades(p.total) },
          { clave: 'acumulado', etiqueta: t('acopio.reportes.col.acumulado'), fuerte: true, valor: (p) => unidades(p.acumulado) },
        ]}
        nota={t('acopio.reportes.entradas.nota')}
      />
      <TablaReporte
        id="reportes-entradas-producto"
        titulo={t('acopio.reportes.entradas.porProducto')}
        clave={(p) => p.productoId}
        filas={porProducto}
        vacio={t('acopio.reportes.entradas.vacio')}
        columnas={[
          { clave: 'producto', etiqueta: t('acopio.reportes.col.producto'), texto: true, fuerte: true, valor: (p) => p.producto },
          { clave: 'productor', etiqueta: t('acopio.reportes.col.productor'), texto: true, valor: (p) => p.productor },
          { clave: 'recepcion', etiqueta: t('acopio.reportes.col.recepcion'), valor: (p) => unidades(p.recepcion) },
          { clave: 'transferencias', etiqueta: t('acopio.reportes.col.transferencias'), valor: (p) => unidades(p.transferencias) },
          { clave: 'total', etiqueta: t('acopio.reportes.col.total'), fuerte: true, valor: (p) => unidades(p.total) },
        ]}
      />
    </>
  )
}
