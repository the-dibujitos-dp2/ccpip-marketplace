import { useTranslation } from 'react-i18next'
import './MapaPunto.css'

/**
 * Busqueda del punto en Google Maps por su direccion. No guardamos coordenadas ni calculamos rutas
 * (RNF-31): el mapa real lo resuelve el servicio del comprador con la direccion publica del punto.
 */
export function urlMapa(punto) {
  const consulta = [punto.nombre, punto.direccion, punto.distrito, punto.provincia, punto.departamento, 'Perú']
    .filter(Boolean)
    .join(', ')
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(consulta)}`
}

/**
 * CCPIP-444: donde queda el punto de acopio para recoger el pedido. Un plano referencial (los mapas de la
 * plataforma son simulados, RNF-31) con el pin, la direccion y un enlace para abrirla en Google Maps y ver
 * como llegar. `compacto` lo muestra mas bajo, para el resumen de la compra.
 */
export default function MapaPunto({ punto, compacto = false }) {
  const { t } = useTranslation()
  const direccion = [punto.direccion, punto.distrito, punto.provincia, punto.departamento].filter(Boolean).join(', ')
  return (
    <figure className={`mapa-punto${compacto ? ' mapa-punto--compacto' : ''}`}>
      <svg className="mapa-punto__plano" viewBox="0 0 320 160" role="img" preserveAspectRatio="xMidYMid slice"
        aria-label={t('mapaPunto.plano', { nombre: punto.nombre, direccion })}>
        <rect className="mapa-punto__fondo" x="0" y="0" width="320" height="160" />
        <path className="mapa-punto__rio" d="M-10 128 C 60 108, 110 150, 180 126 S 290 100, 330 118" />
        <rect className="mapa-punto__parque" x="206" y="22" width="62" height="40" rx="6" />
        <g className="mapa-punto__calles">
          <path d="M0 50 H320" />
          <path d="M0 92 H320" />
          <path d="M70 0 V160" />
          <path d="M160 0 V160" />
          <path d="M250 0 V160" />
        </g>
        <g className="mapa-punto__calles mapa-punto__calles--menores">
          <path d="M0 18 H320" />
          <path d="M115 0 V160" />
          <path d="M205 0 V160" />
          <path d="M20 0 L120 160" />
        </g>
        <g className="mapa-punto__pin" transform="translate(160 92)">
          <ellipse cx="0" cy="2" rx="10" ry="3.5" className="mapa-punto__sombra" />
          <path d="M0 0 C -4 -9, -14 -16, -14 -27 A 14 14 0 1 1 14 -27 C 14 -16, 4 -9, 0 0 Z" />
          <circle cx="0" cy="-27" r="5" className="mapa-punto__centro" />
        </g>
      </svg>
      <figcaption className="mapa-punto__pie">
        <span className="caption">{t('mapaPunto.referencial')}</span>
        <a className="mapa-punto__enlace" href={urlMapa(punto)} target="_blank" rel="noopener noreferrer">
          {t('mapaPunto.comoLlegar')}{' '}
          <span className="visually-hidden">{t('mapaPunto.nuevaPestana')}</span>
        </a>
      </figcaption>
    </figure>
  )
}
