import { useTranslation } from 'react-i18next'
import iconoCamion from '../../../assets/icons/camion.svg'
import iconoGlobo from '../../../assets/icons/globo.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoPaquete from '../../../assets/icons/paquete.svg'
import { Kpis } from '../../../components/TablaReporte.jsx'
import { precio, unidades } from '../../../formato.js'
import { columnas, rango, TablaReporte } from './comun.jsx'

const TEXTOS = 'admin.reportes.origen-destino'

const suma = (filas, campo) => filas.reduce((total, f) => total + Number(f[campo]), 0)

/**
 * CCPIP-80 (RF-66, SA-10): de donde salen las ventas del rango (punto de acopio y productor) y hacia donde
 * van (el departamento de la entrega o del punto donde se recoge), de mayor a menor. Solo agregados:
 * ninguna direccion ni comprador.
 */
export default function OrigenDestino({ datos }) {
  const { t } = useTranslation()
  const { texto, cantidad, importe, porcentaje } = columnas(t)
  const sinDireccion = t(`${TEXTOS}.sinDireccion`)
  const departamento = (clave) => texto(clave, { valor: (f) => f[clave] ?? sinDireccion })
  const origen = (nombre, detalle) => [
    texto(nombre, { fuerte: true, valor: (f) => f.nombre }), texto(detalle, { valor: (f) => f.detalle }),
    cantidad('pedidos'), importe('total'), porcentaje('participacion'),
  ]
  const claveOrigen = (f) => `${f.nombre}|${f.detalle}`
  const destinos = datos.porDestino.filter((d) => d.departamento != null).length
  return (
    <>
      <Kpis textos={TEXTOS} tarjetas={[
        { clave: 'ventas', valor: precio(datos.total), icono: iconoGrafico, vars: rango(datos) },
        { clave: 'pedidos', valor: unidades(datos.pedidos), icono: iconoPaquete },
        { clave: 'destinos', valor: unidades(destinos), icono: iconoGlobo },
        { clave: 'envios', valor: unidades(suma(datos.porDestino, 'envios')), icono: iconoCamion,
          vars: { recojos: suma(datos.porDestino, 'recojos') } },
      ]} />
      <TablaReporte id="reportes-origen-punto" titulo={t(`${TEXTOS}.porPunto`)} filas={datos.porPunto} clave={claveOrigen}
        vacio={t(`${TEXTOS}.vacio`)} columnas={origen('punto', 'departamento')} />
      <TablaReporte id="reportes-origen-productor" titulo={t(`${TEXTOS}.porProductor`)} filas={datos.porProductor} clave={claveOrigen}
        vacio={t(`${TEXTOS}.vacio`)} columnas={origen('productor', 'pueblo')} />
      <TablaReporte id="reportes-destino" titulo={t(`${TEXTOS}.porDestino`)} filas={datos.porDestino}
        clave={(f) => f.departamento ?? ''} vacio={t(`${TEXTOS}.vacio`)}
        columnas={[
          departamento('departamento'), cantidad('pedidos'), cantidad('envios'), cantidad('recojos'),
          importe('total'), porcentaje('participacion'),
        ]} />
      <TablaReporte id="reportes-rutas" titulo={t(`${TEXTOS}.rutas`)} filas={datos.rutas}
        clave={(f) => `${f.punto}|${f.destino}`} vacio={t(`${TEXTOS}.vacio`)} nota={t(`${TEXTOS}.nota`)}
        columnas={[texto('punto', { fuerte: true }), departamento('destino'), cantidad('pedidos'), importe('total')]} />
    </>
  )
}
