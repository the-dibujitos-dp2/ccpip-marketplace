import { useTranslation } from 'react-i18next'
import iconoComunidad from '../../../assets/icons/comunidad.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoPorcentaje from '../../../assets/icons/porcentaje.svg'
import { Kpis } from '../../../components/TablaReporte.jsx'
import { precio, unidades } from '../../../formato.js'
import { columnas, porcentaje, rango, TablaReporte } from './comun.jsx'

const TEXTOS = 'admin.reportes.productores'

/**
 * CCPIP-122 (RF-86, SA-10): productores que mas venden en el rango, de mayor a menor, con su desglose B2C y
 * B2B y su participacion en las ventas de la plataforma.
 */
export default function Productores({ datos }) {
  const { t } = useTranslation()
  const { texto, cantidad, importe, porcentaje: parte } = columnas(t)
  const [lider] = datos.productores
  return (
    <>
      <Kpis textos={TEXTOS} tarjetas={[
        { clave: 'ventas', valor: precio(datos.totales.total), icono: iconoGrafico, vars: rango(datos) },
        { clave: 'conVentas', valor: unidades(datos.productoresConVentas), icono: iconoComunidad, vars: { limite: datos.limite } },
        lider
          ? { clave: 'lider', valor: porcentaje(lider.participacion), icono: iconoPorcentaje, vars: { productor: lider.productor } }
          : { clave: 'lider', valor: '—', icono: iconoPorcentaje, detalle: 'sinLider' },
      ]} />
      <TablaReporte id="reportes-productores" titulo={t(`${TEXTOS}.tabla`)} filas={datos.productores}
        clave={(p) => p.productorId} vacio={t(`${TEXTOS}.vacio`)} nota={t(`${TEXTOS}.nota`, { limite: datos.limite })}
        columnas={[
          texto('productor', { fuerte: true }), texto('pueblo'), cantidad('pedidos'), cantidad('unidades'),
          importe('b2c'), importe('b2b'), importe('total'), parte('participacion'),
        ]} />
    </>
  )
}
