import { createContext, useContext } from 'react'

/** Sesion actual para cualquier pantalla: usuario, cierre de sesion (CCPIP-27) y cambio del nombre (CCPIP-113). */
export const SesionContexto = createContext({ user: null, cerrarSesion: () => {}, cerrando: false, actualizarUsuario: () => {} })

export function useSesion() {
  return useContext(SesionContexto)
}
