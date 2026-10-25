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
  misDatos: '/cuenta/datos',
  productor: '/productor',
  productorTienda: '/productor/tienda',
  productorProductos: '/productor/productos',
  productorNuevo: '/productor/productos/nuevo',
  productorLotes: '/productor/lotes',
  productorAlmacen: '/productor/almacen',
  productorCuenta: '/productor/cuenta-bancaria',
  productorPedidos: '/productor/pedidos',
  productorLiquidaciones: '/productor/liquidaciones',
  productorDenuncias: '/productor/denuncias',
  productorTransferencias: '/productor/transferencias',
  productorTransferenciaNueva: '/productor/transferencias/nueva',
  productorAlertas: '/productor/alertas',
  productorMerma: '/productor/merma',
  productorReportes: '/productor/reportes',
  acopio: '/acopio',
  acopioPedidos: '/acopio/pedidos',
  acopioMovimientos: '/acopio/movimientos',
  acopioInventario: '/acopio/inventario',
  acopioTransferencias: '/acopio/transferencias',
  acopioAlertas: '/acopio/alertas',
  acopioMerma: '/acopio/merma',
  distribuidor: '/distribuidor',
  distribuidorPedidos: '/distribuidor/pedidos',
  admin: '/admin',
  adminPermisos: '/admin/permisos',
  adminComision: '/admin/comision',
  adminComisiones: '/admin/comisiones',
  adminReportes: '/admin/reportes',
  adminParametros: '/admin/parametros',
  adminMotivosMerma: '/admin/motivos-merma',
  adminAutorizaciones: '/admin/autorizaciones',
  adminProductores: '/admin/productores',
  adminDenuncias: '/admin/denuncias',
  adminMiHistorial: '/admin/mi-historial',
  adminCatalogo: '/admin/catalogo',
  adminExistencias: '/admin/existencias',
  adminCuentas: '/admin/cuentas',
  adminCuentaNueva: '/admin/cuentas/nueva',
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
