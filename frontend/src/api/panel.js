// Panel de inicio de cada rol. El servidor valida el rol (CCPIP-151).
import { apiFetch } from './http.js'

const PANEL_POR_ROL = {
  CONSUMIDOR: 'consumidor',
  PRODUCTOR: 'productor',
  ADMIN_PUNTO: 'acopio',
  SUB_ADMIN: 'administracion',
  SUPER_ADMIN: 'administracion',
  DISTRIBUIDOR: 'distribuidor',
}

export function fetchPanel(role) {
  return apiFetch(`/api/panel/${PANEL_POR_ROL[role]}`)
}
