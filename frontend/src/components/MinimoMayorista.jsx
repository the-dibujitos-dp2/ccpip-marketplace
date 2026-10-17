import iconoAplicado from '../assets/icons/check-circulo.svg'
import iconoBloqueado from '../assets/icons/alerta-error.svg'
import iconoFalta from '../assets/icons/paquete.svg'
import Icono from './Icono.jsx'
import './MinimoMayorista.css'

const ICONOS = { falta: iconoFalta, alcanzado: iconoAplicado, bloqueado: iconoBloqueado }

/**
 * CCPIP-134, "Cantidad mínima mayorista" del Figma (DS · Acuerdos 30/09): aviso inmediato en la ficha
 * y el carrito con cuanto falta para el minimo. estado: falta | alcanzado | bloqueado. La barra muestra
 * cuanto se avanzo hacia el minimo (`cantidad` de `minimo`).
 */
export default function MinimoMayorista({ estado, titulo, detalle, cantidad, minimo }) {
  const avance = minimo ? Math.min(1, Math.max(0, cantidad / minimo)) : 1
  return (
    <div className={`minimo-mayorista minimo-mayorista--${estado}`} role="status">
      <p className="minimo-mayorista__titulo">
        <Icono src={ICONOS[estado]} tamano={18} />
        {titulo}
      </p>
      <div className="minimo-mayorista__barra" aria-hidden="true">
        <span style={{ width: `${Math.round(avance * 100)}%` }} />
      </div>
      {detalle && <p className="caption minimo-mayorista__detalle">{detalle}</p>}
    </div>
  )
}
