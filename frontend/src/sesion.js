import { createContext, useContext } from 'react'

/** Sesion actual para cualquier pantalla: usuario y cierre de sesion (CCPIP-27). */
export const SesionContexto = createContext({ user: null, cerrarSesion: () => {}, cerrando: false })

export function useSesion() {
  return useContext(SesionContexto)
}
