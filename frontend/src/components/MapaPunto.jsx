import { useTranslation } from 'react-i18next'
import './MapaPunto.css'

/** La direccion publica del punto, como la busca Google Maps. No guardamos coordenadas (RNF-31). */
const consulta = (punto) =>
  [punto.nombre, punto.direccion, punto.distrito, punto.provincia, punto.departamento, 'Perú'].filter(Boolean).join(', ')

/**
 * CCPIP-510: el mapa real de la zona del punto, incrustado. Google Maps ubica la direccion publica del punto
 * (sin coordenadas ni clave nuestras, RNF-31) y lo centra en el recuadro; desde el mismo mapa se abre en Google Maps
 * para ver como llegar, sin un enlace aparte.
 */
export function urlMapaIncrustado(punto) {
  return `https://maps.google.com/maps?q=${encodeURIComponent(consulta(punto))}&z=15&output=embed`
}

/**
 * CCPIP-444: donde queda el punto de acopio para recoger el pedido, en el mapa de su zona. `compacto` lo muestra mas
 * bajo, para el resumen de la compra.
 * Solo se muestra la direccion publica del punto: nunca la del comprador.
 */
export default function MapaPunto({ punto, compacto = false }) {
  const { t } = useTranslation()
  const direccion = [punto.direccion, punto.distrito, punto.provincia, punto.departamento].filter(Boolean).join(', ')
  return (
    <figure className={`mapa-punto${compacto ? ' mapa-punto--compacto' : ''}`}>
      <iframe
        className="mapa-punto__plano"
        src={urlMapaIncrustado(punto)}
        title={t('mapaPunto.plano', { nombre: punto.nombre, direccion })}
        loading="lazy"
        referrerPolicy="no-referrer"
      />
      <figcaption className="mapa-punto__pie">
        <span className="caption">{t('mapaPunto.referencial')}</span>
      </figcaption>
    </figure>
  )
}
