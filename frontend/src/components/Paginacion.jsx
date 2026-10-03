import Boton from './Boton.jsx'
import './Paginacion.css'

/** Paginacion del sistema Monte. `pagina` empieza en 0, como en la API. */
export default function Paginacion({ pagina, tamano, total, totalPaginas, onCambiar }) {
  if (total === 0) return null
  const desde = pagina * tamano + 1
  const hasta = Math.min(total, (pagina + 1) * tamano)
  return (
    <nav className="paginacion" aria-label="Paginación">
      <p className="caption">
        Mostrando {desde}–{hasta} de {total}
      </p>
      <div className="paginacion__botones">
        <Boton tipo="secundario" tamano="S" disabled={pagina === 0} onClick={() => onCambiar(pagina - 1)}>
          Anterior
        </Boton>
        <Boton tipo="secundario" tamano="S" disabled={pagina >= totalPaginas - 1} onClick={() => onCambiar(pagina + 1)}>
          Siguiente
        </Boton>
      </div>
    </nav>
  )
}
