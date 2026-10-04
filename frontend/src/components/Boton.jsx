import Icono from './Icono.jsx'
import { navigate } from '../router.js'
import './Boton.css'

const TAMANO_ICONO = { L: 20, M: 18, S: 16 }

/**
 * Boton del sistema Monte. Primario = accion principal (max. 1 por vista). Acento = compra.
 * Destructivo = siempre con confirmacion. Con `to` se comporta como enlace interno.
 * tipo: primario | secundario | terciario | acento | destructivo · tamano: L | M | S
 */
export default function Boton({ tipo = 'primario', tamano = 'L', anchoCompleto = false, icono, to, children, className = '', ...props }) {
  const clases = ['boton', `boton--${tipo}`, `boton--${tamano}`, anchoCompleto && 'boton--ancho', className]
    .filter(Boolean)
    .join(' ')
  const contenido = (
    <>
      {icono && <Icono src={icono} tamano={TAMANO_ICONO[tamano]} />}
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
        {...props}
      >
        {contenido}
      </a>
    )
  }
  return (
    <button className={clases} type="button" {...props}>
      {contenido}
    </button>
  )
}
