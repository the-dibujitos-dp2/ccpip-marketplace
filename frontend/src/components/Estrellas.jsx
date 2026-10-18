import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { unidades } from '../formato.js'
import './Estrellas.css'

const ESCALA = [1, 2, 3, 4, 5]

/** Calificacion en estrellas de 1 a 5 (RF-88). Se lee con su valor, no solo por el color. */
export default function Estrellas({ valor }) {
  const { t } = useTranslation()
  const redondeado = Math.round(Number(valor))
  return (
    <span className="estrellas" role="img" aria-label={t('calificaciones.deCinco', { valor: unidades(valor) })}>
      {ESCALA.map((n) => (
        <span key={n} aria-hidden="true" className={n <= redondeado ? undefined : 'estrellas__vacia'}>★</span>
      ))}
    </span>
  )
}

/** Elegir de 1 a 5 estrellas: un grupo de radios nativos, accesible con teclado. */
export function SelectorEstrellas({ valor, onCambiar, etiqueta, disabled }) {
  const { t } = useTranslation()
  const nombre = useId()
  return (
    <fieldset className="selector-estrellas" disabled={disabled}>
      <legend>{etiqueta}</legend>
      {ESCALA.map((n) => (
        <label key={n} className={`selector-estrellas__opcion${n <= (valor ?? 0) ? ' selector-estrellas__opcion--activa' : ''}`}>
          <input type="radio" name={nombre} value={n} checked={valor === n} onChange={() => onCambiar(n)}
            aria-label={t('calificaciones.estrellas', { count: n })} />
          <span aria-hidden="true">★</span>
        </label>
      ))}
    </fieldset>
  )
}
