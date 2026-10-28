import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { obtenerSellos } from '../../api/catalogo.js'
import { useConsulta } from '../../api/useConsulta.js'
import SelloOrigen from '../../components/SelloOrigen.jsx'

/**
 * CCPIP-38 (RF-15): el sello completo de la ficha, con lo que el servidor respalda: el origen verificado
 * por la Camara y los certificados vigentes que sustentaron la aprobacion. Mientras carga, si falla o si
 * no hay ningun respaldo, no afirma nada: solo lo dice.
 */
export default function SellosProducto({ productoId }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => obtenerSellos(productoId), [productoId])
  const { datos, error } = useConsulta(consultar)
  if (!datos && !error) return null
  if (!datos?.sellos?.length) return <p className="body-m">{t('producto.sinSellos')}</p>
  return <SelloOrigen variante="completo" sellos={datos.sellos} />
}
