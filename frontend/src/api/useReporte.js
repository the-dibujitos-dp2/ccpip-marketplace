import { useCallback, useEffect, useState } from 'react'

/**
 * Estado de una pantalla de reportes por periodo con pestañas (productor, punto de acopio). Carga la
 * primera pestaña sin filtros: el servidor responde el rango y la agrupacion que uso por defecto, y
 * `aFiltro(respuesta, filtroAnterior)` los pasa a los campos de la pantalla. `datos.clave` es la pestaña
 * que respondio, para que lo que se ve no cambie antes de que llegue la respuesta.
 */
export function useReporte(consultar, aFiltro, inicial, filtroVacio) {
  const [filtro, setFiltro] = useState(filtroVacio)
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)

  const cargar = useCallback(async (clave, valores) => {
    setCargando(true)
    setError(null)
    try {
      const respuesta = await consultar(clave, valores)
      setDatos({ ...respuesta, clave })
      setFiltro((anterior) => aFiltro(respuesta, anterior))
    } catch (e) {
      setError(e)
    } finally {
      setCargando(false)
    }
  }, [consultar, aFiltro])

  useEffect(() => {
    cargar(inicial)
  }, [cargar, inicial])

  return { filtro, setFiltro, datos, error, cargando, cargar }
}

/** Un id opcional de la respuesta como valor de un selector: vacio si no se filtro. */
export const opcion = (id) => (id == null ? '' : String(id))
