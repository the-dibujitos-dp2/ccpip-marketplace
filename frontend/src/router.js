// Navegacion con la History API.
import { useEffect, useState } from 'react'

export const RUTAS = {
  catalogo: '/',
  buscar: '/buscar',
  carrito: '/carrito',
  compra: '/compra',
  comunidades: '/comunidades',
  ingresar: '/ingresar',
  registro: '/registro',
  verificarCorreo: '/verificar-correo',
  recuperarContrasena: '/recuperar-contrasena',
  restablecerContrasena: '/restablecer-contrasena',
  inicio: '/inicio',
  misPedidos: '/cuenta/pedidos',
  productor: '/productor',
  productorTienda: '/productor/tienda',
  productorProductos: '/productor/productos',
  productorNuevo: '/productor/productos/nuevo',
  productorLotes: '/productor/lotes',
  productorAlmacen: '/productor/almacen',
  productorCuenta: '/productor/cuenta-bancaria',
  productorPedidos: '/productor/pedidos',
  productorTransferencias: '/productor/transferencias',
  productorTransferenciaNueva: '/productor/transferencias/nueva',
  acopio: '/acopio',
  acopioPedidos: '/acopio/pedidos',
  acopioMovimientos: '/acopio/movimientos',
  acopioTransferencias: '/acopio/transferencias',
  admin: '/admin',
  adminComision: '/admin/comision',
  adminComisiones: '/admin/comisiones',
}

export function navigate(path, { replace = false } = {}) {
  if (replace) window.history.replaceState(null, '', path)
  else window.history.pushState(null, '', path)
  window.dispatchEvent(new PopStateEvent('popstate'))
}

/** Ruta y query string actuales; se actualizan con navigate() y con atras/adelante. */
export function useLocation() {
  const leer = () => ({ path: window.location.pathname, search: window.location.search })
  const [location, setLocation] = useState(leer)
  useEffect(() => {
    const update = () => setLocation(leer())
    window.addEventListener('popstate', update)
    return () => window.removeEventListener('popstate', update)
  }, [])
  return location
}

/** Solo rutas internas: evita que ?volver= lleve a otro sitio (open redirect). */
export function rutaInterna(valor, porDefecto) {
  return typeof valor === 'string' && valor.startsWith('/') && !valor.startsWith('//') ? valor : porDefecto
}
