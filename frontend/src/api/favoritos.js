// Favoritos del consumidor: productos (CCPIP-177, CCPIP-327, CCPIP-396), productores (CCPIP-368, CCPIP-385,
// CCPIP-397) y comunidades (CCPIP-369, CCPIP-386, CCPIP-398). El servidor toma la cuenta de la sesion.
import { apiFetch, idRuta } from './http.js'

/**
 * Sus favoritos, el ultimo guardado primero: { productos: [{ id, nombre, guardadoEn, oferta }],
 * productores: [{ id, nombre, guardadoEn, tienda }], comunidades: [{ tiendaId, comunidad, region, guardadoEn, tiendas }] }.
 * `oferta` o `tienda` llegan en null si ya no estan publicadas.
 */
export const listarFavoritos = () => apiFetch('/api/cuenta/favoritos')

/** `tipo`: productos | productores | comunidades. Para comunidades, `id` es una tienda de esa comunidad. */
export const guardarFavorito = (tipo, id) => apiFetch(`/api/cuenta/favoritos/${tipo}/${idRuta(id)}`, { method: 'PUT' })

export const retirarFavorito = (tipo, id) => apiFetch(`/api/cuenta/favoritos/${tipo}/${idRuta(id)}`, { method: 'DELETE' })
