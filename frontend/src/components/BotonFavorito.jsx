import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCorazon from '../assets/icons/corazon.svg'
import iconoCorazonLleno from '../assets/icons/corazon-lleno.svg'
import { guardarFavorito, listarFavoritos, retirarFavorito } from '../api/favoritos.js'
import { mensajeError } from '../i18n/errores.js'
import { navigate, RUTAS } from '../router.js'
import { useSesion } from '../sesion.js'
import Boton from './Boton.jsx'
import './BotonFavorito.css'

/**
 * Los favoritos del consumidor de la sesion, para marcar los botones de una pagina con una sola consulta.
 * `aplica`: sin sesion se ofrece (lleva a ingresar); a otros roles no, porque solo el consumidor tiene favoritos.
 */
export function useFavoritos() {
  const { user } = useSesion()
  const consumidor = user?.role === 'CONSUMIDOR'
  const [favoritos, setFavoritos] = useState(null)
  const recargar = useCallback(() => {
    if (!consumidor) {
      setFavoritos(null)
      return
    }
    listarFavoritos().then(setFavoritos).catch(() => setFavoritos(null))
  }, [consumidor])
  useEffect(recargar, [recargar, user?.id])
  return { favoritos, setFavoritos, aplica: !user || consumidor, conSesion: Boolean(user) }
}

/** Si `id` esta entre sus favoritos de ese `tipo`. Una comunidad cuenta desde cualquiera de sus tiendas. */
export function esFavorito(favoritos, tipo, id) {
  if (!favoritos) return false
  if (tipo === 'comunidades') return favoritos.comunidades.some((c) => c.tiendaId === id || c.tiendas.some((t) => t.id === id))
  return favoritos[tipo].some((f) => f.id === id)
}

/**
 * Corazon para guardar o retirar un favorito: un producto (CCPIP-177, CCPIP-396), un productor (CCPIP-368,
 * CCPIP-397) o la comunidad de una tienda (CCPIP-369, CCPIP-398). No reserva ni registra cantidad.
 * `estado`: lo que devuelve useFavoritos() en la pagina.
 */
export default function BotonFavorito({ tipo, id, estado, tamano = 'M' }) {
  const { t } = useTranslation()
  const { favoritos, setFavoritos, aplica, conSesion } = estado
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  if (!aplica) return null
  const guardado = esFavorito(favoritos, tipo, id)

  async function alternar() {
    if (!conSesion) {
      navigate(`${RUTAS.ingresar}?volver=${encodeURIComponent(window.location.pathname)}`)
      return
    }
    setEnviando(true)
    setError(null)
    try {
      if (guardado) await retirarFavorito(tipo, id)
      else await guardarFavorito(tipo, id)
      setFavoritos(await listarFavoritos())
    } catch (e) {
      if (e.status === 404 && guardado) setFavoritos(await listarFavoritos().catch(() => favoritos))
      else if (e.status !== 401) setError(mensajeError(e))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="boton-favorito">
      <Boton
        tipo="secundario"
        tamano={tamano}
        icono={guardado ? iconoCorazonLleno : iconoCorazon}
        aria-pressed={guardado}
        cargando={enviando}
        onClick={alternar}
      >
        {t(`favoritos.boton.${tipo}.${guardado ? 'guardado' : 'guardar'}`)}
      </Boton>
      {error && <p className="caption boton-favorito__error" role="alert">{error}</p>}
    </div>
  )
}
