import { useTranslation } from 'react-i18next'
import iconoCaja from '../../../assets/icons/caja.svg'
import iconoMovimientos from '../../../assets/icons/movimientos.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import TarjetaKpi from '../../../components/TarjetaKpi.jsx'
import { unidades } from '../../../formato.js'
import { nombrePeriodo, TablaReporte } from './comun.jsx'

/**
 * CCPIP-341 (RF-231): lo que salio de sus puntos por periodo. Las ventas se separan en entregadas
 * (atendidas) y pagadas aun por entregar (no atendidas); aparte, transferencias, devoluciones y merma.
 */
export default function Salidas({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const und = (v) => t('comun.und', { cantidad: unidades(v) })
  const cifra = (clave, opciones = {}) => ({ clave, etiqueta: t(`acopio.reportes.col.${clave}`), valor: (p) => unidades(p[clave]), ...opciones })
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('acopio.reportes.salidas.total')} valor={und(totales.total)} tono="neutro" icono={iconoMovimientos}
          detalle={t('acopio.reportes.salidas.totalDetalle', {
            transferencias: unidades(totales.transferencias), devoluciones: unidades(totales.devoluciones), merma: unidades(totales.merma),
          })} />
        <TarjetaKpi titulo={t('acopio.reportes.salidas.entregadas')} valor={und(totales.ventasEntregadas)} icono={iconoCaja}
          detalle={t('acopio.reportes.salidas.entregadasDetalle')} />
        <TarjetaKpi titulo={t('acopio.reportes.salidas.porEntregar')} valor={und(totales.ventasPorEntregar)} icono={iconoReloj}
          tono={Number(totales.ventasPorEntregar) > 0 ? 'aviso' : 'neutro'} detalle={t('acopio.reportes.salidas.porEntregarDetalle')} />
      </div>
      <TablaReporte
        id="reportes-salidas-periodo"
        titulo={t('acopio.reportes.salidas.porPeriodo')}
        clave={(p) => p.inicio}
        filas={periodos}
        estrecha
        columnas={[
          { clave: 'periodo', etiqueta: t('acopio.reportes.col.periodo'), periodo: true, valor: (p) => nombrePeriodo(p, agrupacion, t) },
          cifra('ventasEntregadas'),
          cifra('ventasPorEntregar'),
          cifra('transferencias'),
          cifra('devoluciones'),
          cifra('merma'),
          cifra('total'),
          cifra('acumulado', { fuerte: true }),
        ]}
        nota={t('acopio.reportes.salidas.nota')}
      />
      <TablaReporte
        id="reportes-salidas-producto"
        titulo={t('acopio.reportes.salidas.porProducto')}
        clave={(p) => p.productoId}
        filas={porProducto}
        vacio={t('acopio.reportes.salidas.vacio')}
        columnas={[
          { clave: 'producto', etiqueta: t('acopio.reportes.col.producto'), texto: true, fuerte: true, valor: (p) => p.producto },
          { clave: 'productor', etiqueta: t('acopio.reportes.col.productor'), texto: true, valor: (p) => p.productor },
          cifra('ventas'),
          cifra('transferencias'),
          cifra('devoluciones'),
          cifra('merma'),
          cifra('total', { fuerte: true }),
        ]}
      />
    </>
  )
}
