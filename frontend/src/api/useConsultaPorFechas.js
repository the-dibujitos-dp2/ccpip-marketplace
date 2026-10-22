import { useCallback, useEffect, useState } from 'react'

/**
 * Consulta que se filtra por un rango de fechas (CCPIP-91, CCPIP-92). Carga al montar sin fechas, y el
 * servidor responde el rango que uso por defecto; desde ahi, cada cambio de fecha vuelve a consultar
 * en cuanto ambas estan completas. `consultar` recibe `{ desde, hasta }` (o nada en la primera carga)
 * y debe responder un objeto con `desde` y `hasta`.
 */
export function useConsultaPorFechas(consultar) {
  const [rango, setRango] = useState({ desde: '', hasta: '' })
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)

  const cargar = useCallback(async (filtro) => {
    setCargando(true)
    setError(null)
    try {
      const respuesta = await consultar(filtro)
      setDatos(respuesta)
      setRango({ desde: respuesta.desde, hasta: respuesta.hasta })
    } catch (e) {
      setError(e)
    } finally {
      setCargando(false)
    }
  }, [consultar])

  useEffect(() => {
    cargar()
  }, [cargar])

  const cambiar = (campo) => (e) => {
    const nuevo = { ...rango, [campo]: e.target.value }
    setRango(nuevo)
    if (nuevo.desde && nuevo.hasta) cargar(nuevo)
  }

  return { rango, cambiar, datos, error, cargando }
}
