import { useTranslation } from 'react-i18next'
import iconoAlmacen from '../../../assets/icons/almacen.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoPaquete from '../../../assets/icons/paquete.svg'
import { Kpis } from '../../../components/TablaReporte.jsx'
import { precio, unidades } from '../../../formato.js'
import { columnas, rango, TablaReporte } from './comun.jsx'

const TEXTOS = 'admin.reportes.puntos'

/**
 * CCPIP-355 (RF-245, SA-10): ventas de cada punto de acopio en el rango, de mayor a menor, con los puntos
 * activos sin ventas en cero. Solo cifras agregadas: sin stock editable ni pedidos individuales.
 */
export default function Puntos({ datos }) {
  const { t } = useTranslation()
  const { texto, cantidad, importe, porcentaje } = columnas(t)
  const conVentas = datos.puntos.filter((p) => Number(p.total) > 0).length
  return (
    <>
      <Kpis textos={TEXTOS} tarjetas={[
        { clave: 'ventas', valor: precio(datos.totales.total), icono: iconoGrafico, vars: rango(datos) },
        { clave: 'pedidos', valor: unidades(datos.pedidos), icono: iconoPaquete },
        { clave: 'conVentas', valor: unidades(conVentas), icono: iconoAlmacen, vars: { total: datos.puntos.length } },
      ]} />
      <TablaReporte id="reportes-puntos" titulo={t(`${TEXTOS}.tabla`)} filas={datos.puntos} clave={(p) => p.puntoId}
        vacio={t(`${TEXTOS}.vacio`)} nota={t(`${TEXTOS}.nota`)}
        columnas={[
          texto('punto', { fuerte: true }), texto('departamento'), cantidad('pedidos'),
          importe('b2c'), importe('b2b'), importe('total'), porcentaje('participacion'),
        ]} />
    </>
  )
}
