import { useTranslation } from 'react-i18next'
import TablaBase, { fabricaColumnas } from '../../../components/TablaReporte.jsx'
import { fecha, precio, unidades } from '../../../formato.js'

/** 12.5 -> "12.5%": las participaciones y variaciones llegan con un decimal. */
export const porcentaje = (valor) => `${unidades(valor)}%`

/** El rango que respondio el servidor, para el detalle de una tarjeta. */
export const rango = (datos) => ({ desde: fecha(datos.desde), hasta: fecha(datos.hasta) })

/**
 * Columnas de los reportes de gestion, con el encabezado `admin.reportes.col.{clave}`: textos (que se
 * estiran), cantidades, importes y porcentajes.
 */
export function columnas(t) {
  const columna = fabricaColumnas(t, 'admin.reportes.col')
  return {
    texto: columna((v) => v ?? '', { texto: true }),
    cantidad: columna(unidades),
    importe: columna(precio),
    porcentaje: columna(porcentaje),
  }
}

/** Una tabla de un reporte de gestion: cifras que se reparten el ancho y el estado vacio comun. */
export function TablaReporte(props) {
  const { t } = useTranslation()
  return <TablaBase estrecha vacioTexto={t('admin.reportes.vacioTexto')} {...props} />
}
