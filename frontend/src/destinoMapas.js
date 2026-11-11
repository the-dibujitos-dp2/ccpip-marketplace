/**
 * CCPIP-511: la direccion de entrega en Waze y en Google Maps, para que el distribuidor navegue hasta ella. En el
 * celular se abre la app si esta instalada. La direccion solo sale cuando el distribuidor lo pide; no guardamos
 * coordenadas (RNF-31). `destino`: { direccion, distrito, provincia, departamento }.
 */
const direccionDestino = (destino) =>
  [destino.direccion, destino.distrito, destino.provincia, destino.departamento, 'Perú'].filter(Boolean).join(', ')

export const urlDestinoWaze = (destino) => `https://waze.com/ul?q=${encodeURIComponent(direccionDestino(destino))}&navigate=yes`

export const urlDestinoGoogleMaps = (destino) =>
  `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(direccionDestino(destino))}`
