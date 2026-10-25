import { useTranslation } from 'react-i18next'
import iconoAlmacen from '../../../assets/icons/almacen.svg'
import iconoMovimientos from '../../../assets/icons/movimientos.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import TarjetaKpi from '../../../components/TarjetaKpi.jsx'
import { unidades } from '../../../formato.js'
import { nombrePeriodo, TablaReporte } from './comun.jsx'

/**
 * CCPIP-343 (RF-233): cuanto hubo en sus puntos y por que cambio, periodo a periodo: cada saldo final es el
 * inicial mas las entradas menos las salidas. Como no atendido, lo vendido que sigue en el punto esperando
 * entrega: ya no esta en el saldo, pero si en el almacen.
 */
export default function Inventario({ datos }) {
  const { t } = useTranslation()
  const { totales, periodos, porProducto, agrupacion } = datos
  const und = (v) => t('comun.und', { cantidad: unidades(v) })
  const cifra = (clave, opciones = {}) => ({ clave, etiqueta: t(`acopio.reportes.col.${clave}`), valor: (p) => unidades(p[clave]), ...opciones })
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('acopio.reportes.inventario.saldoInicial')} valor={und(totales.saldoInicial)} tono="neutro" icono={iconoAlmacen}
          detalle={t('acopio.reportes.inventario.alInicio')} />
        <TarjetaKpi titulo={t('acopio.reportes.inventario.movimiento')} valor={und(Number(totales.entradas) - Number(totales.salidas))}
          tono="neutro" icono={iconoMovimientos}
          detalle={t('acopio.reportes.inventario.movimientoDetalle', { entradas: unidades(totales.entradas), salidas: unidades(totales.salidas) })} />
        <TarjetaKpi titulo={t('acopio.reportes.inventario.saldoFinal')} valor={und(totales.saldoFinal)} tono="neutro" icono={iconoAlmacen}
          detalle={t('acopio.reportes.inventario.alFinal')} />
        <TarjetaKpi titulo={t('acopio.reportes.inventario.porEntregar')} valor={und(totales.porEntregar)} icono={iconoReloj}
          tono={Number(totales.porEntregar) > 0 ? 'aviso' : 'neutro'}
          detalle={t('acopio.reportes.inventario.porEntregarDetalle', { cantidad: und(totales.existenciaFisica) })} />
      </div>
      <TablaReporte
        id="reportes-inventario-periodo"
        titulo={t('acopio.reportes.inventario.porPeriodo')}
        clave={(p) => p.inicio}
        filas={periodos}
        estrecha
        columnas={[
          { clave: 'periodo', etiqueta: t('acopio.reportes.col.periodo'), periodo: true, valor: (p) => nombrePeriodo(p, agrupacion, t) },
          cifra('saldoInicial'),
          cifra('entradas'),
          cifra('salidas'),
          cifra('saldoFinal', { fuerte: true }),
        ]}
        nota={t('acopio.reportes.inventario.nota')}
      />
      <TablaReporte
        id="reportes-inventario-producto"
        titulo={t('acopio.reportes.inventario.porProducto')}
        clave={(p) => p.productoId}
        filas={porProducto}
        vacio={t('acopio.reportes.inventario.vacio')}
        estrecha
        columnas={[
          { clave: 'producto', etiqueta: t('acopio.reportes.col.producto'), texto: true, fuerte: true, valor: (p) => p.producto },
          { clave: 'productor', etiqueta: t('acopio.reportes.col.productor'), texto: true, valor: (p) => p.productor },
          cifra('saldoInicial'),
          cifra('entradas'),
          cifra('salidas'),
          cifra('saldoFinal', { fuerte: true }),
          cifra('ventasPorEntregar', { valor: (p) => unidades(p.porEntregar) }),
        ]}
      />
    </>
  )
}
