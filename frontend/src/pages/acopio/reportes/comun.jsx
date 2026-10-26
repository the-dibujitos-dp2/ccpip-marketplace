import { useTranslation } from 'react-i18next'
import TablaBase, { fabricaColumnas } from '../../../components/TablaReporte.jsx'
import { capital, fecha, mesAnio, precio, unidades } from '../../../formato.js'

export { Barra, Kpis } from '../../../components/TablaReporte.jsx'

/** Nombre del periodo: el mes, la semana desde su lunes (recortada al rango) o el dia. */
export function nombrePeriodo(p, agrupacion, t) {
  if (agrupacion === 'MES') return capital(mesAnio(p.inicio))
  if (agrupacion === 'SEMANA') return t('acopio.reportes.semana', { inicio: fecha(p.inicio), fin: fecha(p.fin) })
  return fecha(p.inicio)
}

/**
 * Las columnas que comparten los cuatro reportes: el periodo, el producto con su productor y las cifras
 * (cantidades o importes) cuyo encabezado es `acopio.reportes.col.{clave}`.
 */
export function columnas(t, agrupacion) {
  const cifra = fabricaColumnas(t, 'acopio.reportes.col')
  return {
    periodo: { clave: 'periodo', etiqueta: t('acopio.reportes.col.periodo'), periodo: true, valor: (p) => nombrePeriodo(p, agrupacion, t) },
    producto: [
      { clave: 'producto', etiqueta: t('acopio.reportes.col.producto'), texto: true, fuerte: true, valor: (p) => p.producto },
      { clave: 'productor', etiqueta: t('acopio.reportes.col.productor'), texto: true, valor: (p) => p.productor },
    ],
    cantidad: cifra(unidades),
    importe: cifra(precio),
  }
}

/** Tono de una tarjeta de pendientes: aviso si hay algo pendiente. */
export const pendiente = (cantidad) => (Number(cantidad) > 0 ? 'aviso' : 'neutro')

/** Las dos tablas de cada reporte: por periodo (con su nota) y por producto (con su estado vacio). */
export function Tablas({ id, textos, datos, porPeriodo, porProducto, estrecha }) {
  const { t } = useTranslation()
  return (
    <>
      <TablaReporte id={`reportes-${id}-periodo`} titulo={t(`${textos}.porPeriodo`)} clave={(p) => p.inicio}
        filas={datos.periodos} columnas={porPeriodo} estrecha={estrecha?.periodo} nota={t(`${textos}.nota`)} />
      <TablaReporte id={`reportes-${id}-producto`} titulo={t(`${textos}.porProducto`)} clave={(p) => p.productoId}
        filas={datos.porProducto} columnas={porProducto} estrecha={estrecha?.producto} vacio={t(`${textos}.vacio`)} />
    </>
  )
}

/** Una tabla del reporte, con el estado vacio de los reportes del punto. */
function TablaReporte(props) {
  const { t } = useTranslation()
  return <TablaBase vacioTexto={t('acopio.reportes.vacioTexto')} {...props} />
}
