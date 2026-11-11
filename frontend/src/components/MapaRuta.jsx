import { useTranslation } from 'react-i18next'
import './MapaPunto.css'

const texto = (partes) => partes.filter(Boolean).join(', ')

/**
 * Ruta en Google Maps del punto de acopio a la direccion de entrega. No guardamos coordenadas ni calculamos
 * recorridos (RNF-31): el servicio del distribuidor resuelve el camino con las dos direcciones.
 */
export function urlRuta(origen, destino) {
  const q = new URLSearchParams({
    api: '1',
    origin: texto([origen.direccion, origen.distrito, 'Perú']),
    destination: texto([destino.direccion, destino.distrito, destino.provincia, destino.departamento, 'Perú']),
    travelmode: 'driving',
  })
  return `https://www.google.com/maps/dir/?${q.toString()}`
}

/**
 * CCPIP-499: el reparto en un plano referencial (los mapas de la plataforma son simulados, RNF-31): el punto de
 * salida, la direccion de entrega y el trayecto entre ambos, con el enlace para abrir la ruta en Google Maps.
 * `origen`: { nombre, direccion, distrito }; `destino`: { direccion, distrito, provincia, departamento }.
 */
export default function MapaRuta({ origen, destino }) {
  const { t } = useTranslation()
  const salida = texto([origen.nombre, origen.direccion, origen.distrito])
  const llegada = texto([destino.direccion, destino.distrito, destino.provincia])
  return (
    <figure className="mapa-punto mapa-ruta">
      <svg className="mapa-punto__plano" viewBox="0 0 320 160" role="img" preserveAspectRatio="xMidYMid slice"
        aria-label={t('mapaRuta.plano', { salida, llegada })}>
        <rect className="mapa-punto__fondo" x="0" y="0" width="320" height="160" />
        <path className="mapa-punto__rio" d="M-10 140 C 60 120, 120 156, 190 136 S 290 112, 330 128" />
        <rect className="mapa-punto__parque" x="132" y="16" width="56" height="34" rx="6" />
        <g className="mapa-punto__calles">
          <path d="M0 46 H320" />
          <path d="M0 100 H320" />
          <path d="M70 0 V160" />
          <path d="M160 0 V160" />
          <path d="M250 0 V160" />
        </g>
        <g className="mapa-punto__calles mapa-punto__calles--menores">
          <path d="M0 18 H320" />
          <path d="M115 0 V160" />
          <path d="M205 0 V160" />
        </g>
        <path className="mapa-ruta__trayecto" d="M70 100 H160 V46 H250" />
        <g className="mapa-ruta__salida" transform="translate(70 100)">
          <circle r="9" />
          <circle r="4" className="mapa-punto__centro" />
        </g>
        <g className="mapa-punto__pin" transform="translate(250 46)">
          <ellipse cx="0" cy="2" rx="10" ry="3.5" className="mapa-punto__sombra" />
          <path d="M0 0 C -4 -9, -14 -16, -14 -27 A 14 14 0 1 1 14 -27 C 14 -16, 4 -9, 0 0 Z" />
          <circle cx="0" cy="-27" r="5" className="mapa-punto__centro" />
        </g>
      </svg>
      <figcaption className="mapa-punto__pie">
        <span className="caption mapa-ruta__leyenda">
          <span className="mapa-ruta__clave"><span className="mapa-ruta__marca mapa-ruta__marca--salida" aria-hidden="true" />{t('mapaRuta.salida')}</span>
          <span className="mapa-ruta__clave"><span className="mapa-ruta__marca mapa-ruta__marca--destino" aria-hidden="true" />{t('mapaRuta.destino')}</span>
        </span>
        <span className="caption mapa-ruta__nota">{t('mapaRuta.referencial')}</span>
        <a className="mapa-punto__enlace" href={urlRuta(origen, destino)} target="_blank" rel="noopener noreferrer">
          {t('mapaRuta.verRuta')}{' '}
          <span className="visually-hidden">{t('mapaPunto.nuevaPestana')}</span>
        </a>
      </figcaption>
    </figure>
  )
}
