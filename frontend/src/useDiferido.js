import { useEffect, useState } from 'react'

/**
 * CCPIP-417: el valor tal como quedo despues de `ms` sin cambios. Permite buscar mientras se escribe sin
 * consultar al servidor con cada tecla.
 */
export function useDiferido(valor, ms = 300) {
  const [diferido, setDiferido] = useState(valor)
  useEffect(() => {
    const espera = setTimeout(() => setDiferido(valor), ms)
    return () => clearTimeout(espera)
  }, [valor, ms])
  return diferido
}
