import { useCallback, useEffect, useState } from 'react'

/**
 * Carga una consulta al montar y cada vez que `consultar` cambia (memoizala con useCallback), y
 * descarta la respuesta de una consulta ya superada. Un 401 lo maneja la app (vuelve a ingresar), asi
 * que no cuenta como error de la pantalla. `recargar` repite la consulta; `setDatos` ajusta lo cargado
 * sin volver a pedirlo.
 */
export function useConsulta(consultar) {
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)

  const recargar = useCallback(() => {
    let vigente = true
    setError(null)
    consultar()
      .then((d) => vigente && setDatos(d))
      .catch((e) => vigente && e.status !== 401 && setError(e))
    return () => {
      vigente = false
    }
  }, [consultar])

  useEffect(recargar, [recargar])

  return { datos, setDatos, error, recargar }
}
