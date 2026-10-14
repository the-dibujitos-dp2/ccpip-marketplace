import { useTranslation } from 'react-i18next'
import Boton from './Boton.jsx'
import './Paginacion.css'

/** Paginacion numerada (CW-02). `pagina` empieza en 0, como en la API. */
export default function Paginacion({ pagina, totalPaginas, onCambiar }) {
  const { t } = useTranslation()
  if (totalPaginas <= 1) return null
  const numeros = paginasVisibles(pagina, totalPaginas)
  return (
    <nav className="paginacion" aria-label={t('paginacion.etiqueta')}>
      <Boton tipo="secundario" tamano="S" disabled={pagina === 0} onClick={() => onCambiar(pagina - 1)}>
        {t('paginacion.anterior')}
      </Boton>
      {numeros.map((n, i) =>
        n === null ? (
          <span key={`s${i}`} className="paginacion__salto" aria-hidden="true">…</span>
        ) : (
          <button
            key={n}
            type="button"
            className={`paginacion__numero${n === pagina ? ' paginacion__numero--actual' : ''}`}
            aria-current={n === pagina ? 'page' : undefined}
            aria-label={t('paginacion.pagina', { numero: n + 1 })}
            onClick={() => onCambiar(n)}
          >
            {n + 1}
          </button>
        ),
      )}
      <Boton tipo="secundario" tamano="S" disabled={pagina >= totalPaginas - 1} onClick={() => onCambiar(pagina + 1)}>
        {t('paginacion.siguiente')}
      </Boton>
    </nav>
  )
}

/** Primera, ultima y vecinas de la actual; el resto se resume con "…". */
export function paginasVisibles(actual, total) {
  const set = new Set([0, total - 1, actual - 1, actual, actual + 1])
  const lista = [...set].filter((n) => n >= 0 && n < total).sort((a, b) => a - b)
  const conSaltos = []
  lista.forEach((n, i) => {
    if (i > 0 && n - lista[i - 1] > 1) conSaltos.push(null)
    conSaltos.push(n)
  })
  return conSaltos
}
