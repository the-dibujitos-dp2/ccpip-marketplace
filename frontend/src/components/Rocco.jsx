import rama from '../assets/rocco/rocco-rama.svg'
import saluda from '../assets/rocco/rocco-saluda.svg'
import feliz from '../assets/rocco/rocco-feliz.svg'
import emocionado from '../assets/rocco/rocco-emocionado.svg'
import sorprendido from '../assets/rocco/rocco-sorprendido.svg'
import pensativo from '../assets/rocco/rocco-pensativo.svg'
import guino from '../assets/rocco/rocco-guino.svg'
import triste from '../assets/rocco/rocco-triste.svg'
import './Rocco.css'

/**
 * CCPIP-513: Rocco, el gallito de las rocas que acompaña al usuario en momentos puntuales. Cada
 * variante es un SVG del paquete del personaje (13/11). Es decorativo: el lector de pantalla lo
 * ignora y el texto que lo acompaña dice lo importante.
 */
export const VARIANTES = { rama, saluda, feliz, emocionado, sorprendido, pensativo, guino, triste }

export default function Rocco({ variante = 'saluda', tamano = 96, animado = false, className = '' }) {
  const src = VARIANTES[variante] ?? VARIANTES.saluda
  return (
    <img
      src={src}
      width={tamano}
      height={Math.round(tamano * 1.1)}
      alt=""
      aria-hidden="true"
      decoding="async"
      data-rocco={variante}
      className={`rocco${animado ? ' rocco--animado' : ''}${className ? ` ${className}` : ''}`}
    />
  )
}

/** Rocco con un globo de texto al lado: saludos, consejos y la IA trabajando. */
export function RoccoDice({ variante, tamano = 72, animado = false, children, className = '' }) {
  return (
    <div className={`rocco-dice${className ? ` ${className}` : ''}`}>
      <Rocco variante={variante} tamano={tamano} animado={animado} />
      <p className="rocco-dice__globo">{children}</p>
    </div>
  )
}
