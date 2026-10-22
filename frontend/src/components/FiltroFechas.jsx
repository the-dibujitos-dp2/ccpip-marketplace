import Campo from './Campo.jsx'

/** Dos fechas, desde y hasta, que no se cruzan. `children` va al final de la fila (p. ej. descargas). */
export default function FiltroFechas({ rango, cambiar, etiquetaDesde, etiquetaHasta, className, children }) {
  return (
    <div className={className}>
      <Campo etiqueta={etiquetaDesde} type="date" value={rango.desde} max={rango.hasta || undefined} onChange={cambiar('desde')} />
      <Campo etiqueta={etiquetaHasta} type="date" value={rango.hasta} min={rango.desde || undefined} onChange={cambiar('hasta')} />
      {children}
    </div>
  )
}
