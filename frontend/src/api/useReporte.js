import { useCallback, useEffect, useRef, useState } from 'react'

/** Dos filtros son los mismos si cada campo vale lo mismo (los ids pueden venir como numero o texto). */
const mismos = (a, b) => Object.keys(a).every((campo) => String(a[campo] ?? '') === String(b?.[campo] ?? ''))

/**
 * Estado de una pantalla de reportes por periodo con pestañas (productor, punto de acopio, plataforma).
 * Carga la primera pestaña sin filtros: el servidor responde el rango y la agrupacion que uso por defecto, y
 * `aFiltro(respuesta, filtroEnviado)` los pasa a los campos de la pantalla. `datos.clave` es la pestaña que
 * respondio y `datos.aplicado`, los filtros con los que se calculo, para que lo que se ve no cambie antes
 * de que llegue la respuesta.
 *
 * Solo cuenta la ultima consulta: una respuesta que llega tarde (o un error) de una consulta anterior se
 * descarta. `vigente` dice si lo que se muestra corresponde a los filtros visibles: es falso mientras se
 * consulta, tras un error o con un filtro editado que aun no se aplica (por ejemplo, una fecha vacia). Las
 * descargas solo se ofrecen si es verdadero, para no bajar un periodo distinto al que se ve.
 */
export function useReporte(consultar, aFiltro, inicial, filtroVacio) {
  const [filtro, setFiltro] = useState(filtroVacio)
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)
  const ultima = useRef(0)
  const filtroActual = useRef(filtroVacio)
  filtroActual.current = filtro

  const cargar = useCallback(async (clave, valores) => {
    const consulta = ++ultima.current
    setCargando(true)
    setError(null)
    try {
      const respuesta = await consultar(clave, valores)
      if (consulta !== ultima.current) return
      const aplicado = aFiltro(respuesta, valores ?? filtroActual.current)
      setDatos({ ...respuesta, clave, aplicado })
      setFiltro(aplicado)
    } catch (e) {
      if (consulta === ultima.current) setError(e)
    } finally {
      if (consulta === ultima.current) setCargando(false)
    }
  }, [consultar, aFiltro])

  useEffect(() => {
    cargar(inicial)
  }, [cargar, inicial])

  /**
   * Muestra el filtro editado y, si el rango esta completo, consulta la pestaña `clave` con el. Con una
   * fecha vacia no consulta y descarta lo que estuviera en camino: el reporte deja de estar vigente.
   */
  const aplicar = useCallback((clave, nuevo) => {
    setFiltro(nuevo)
    if (nuevo.desde && nuevo.hasta) {
      cargar(clave, nuevo)
    } else {
      ultima.current++
      setCargando(false)
    }
  }, [cargar])

  const vigente = Boolean(datos) && !cargando && !error && mismos(filtro, datos.aplicado)

  return { filtro, datos, error, cargando, vigente, cargar, aplicar }
}

/** Un id opcional de la respuesta como valor de un selector: vacio si no se filtro. */
export const opcion = (id) => (id == null ? '' : String(id))
