import Giro from './Giro.jsx'
import Icono from './Icono.jsx'
import { navigate } from '../router.js'
import './Boton.css'

const TAMANO_ICONO = { L: 20, M: 18, S: 16 }

/**
 * Boton del sistema Monte. Primario = accion principal (max. 1 por vista). Acento = compra.
 * Destructivo = siempre con confirmacion. Con `to` se comporta como enlace interno.
 * tipo: primario | secundario | terciario | acento | destructivo · tamano: L | M | S
 *
 * CCPIP-413: `cargando` marca el boton de la accion que espera una respuesta. Muestra un aro que gira en lugar
 * del icono, igual en toda la plataforma, y queda deshabilitado (y `aria-busy`) hasta que termina, sin el gris de
 * un boton bloqueado. Los demas botones que solo se bloquean mientras tanto (Cancelar) usan `disabled`.
 */
export default function Boton({ tipo = 'primario', tamano = 'L', anchoCompleto = false, icono, cargando = false, disabled, to, children, className = '', ...props }) {
  const clases = ['boton', `boton--${tipo}`, `boton--${tamano}`, anchoCompleto && 'boton--ancho', cargando && !to && 'boton--cargando', className]
    .filter(Boolean)
    .join(' ')
  const lado = TAMANO_ICONO[tamano]
  const contenido = (
    <>
      {cargando && !to ? <Giro tamano={lado} /> : icono && <Icono src={icono} tamano={lado} />}
      {children}
    </>
  )
  if (to) {
    return (
      <a
        className={clases}
        href={to}
        onClick={(e) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
          e.preventDefault()
          navigate(to)
        }}
        disabled={disabled}
        {...props}
      >
        {contenido}
      </a>
    )
  }
  return (
    <button className={clases} type="button" {...props} disabled={disabled || cargando} aria-busy={cargando || undefined}>
      {contenido}
    </button>
  )
}
