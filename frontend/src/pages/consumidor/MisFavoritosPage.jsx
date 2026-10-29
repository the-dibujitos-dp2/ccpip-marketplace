import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarFavoritos, retirarFavorito } from '../../api/favoritos.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import TarjetaTienda from '../../components/TarjetaTienda.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import LateralCuenta from './LateralCuenta.jsx'
import './MisPedidosPage.css'
import './MisFavoritosPage.css'

const TIPOS = ['productos', 'productores', 'comunidades']

/**
 * Mi cuenta › Favoritos: sus productos (CCPIP-327), productores (CCPIP-385) y comunidades (CCPIP-386), el
 * ultimo guardado primero. Desde aqui los retira (CCPIP-396, CCPIP-397, CCPIP-398) sin tocar el producto, la
 * tienda ni la comunidad. Lo que ya no esta publicado se sigue viendo, marcado, hasta que lo retire.
 */
export default function MisFavoritosPage() {
  const { t } = useTranslation()
  const { datos: favoritos, error, recargar } = useConsulta(listarFavoritos)
  const [tipo, setTipo] = useState('productos')
  const [retirando, setRetirando] = useState(null)
  const [aviso, setAviso] = useState(null)

  async function retirar(clave, id, nombre) {
    setRetirando(`${clave}-${id}`)
    setAviso(null)
    try {
      await retirarFavorito(clave, id)
      setAviso({ tono: 'exito', titulo: t('favoritos.retirado', { nombre }) })
      recargar()
    } catch (e) {
      if (e.status === 404) recargar()
      else setAviso({ tono: 'error', titulo: t('favoritos.errorRetirar'), descripcion: mensajeError(e) })
    } finally {
      setRetirando(null)
    }
  }

  function botonRetirar(clave, id, nombre) {
    return (
      <Boton tipo="terciario" tamano="S" cargando={retirando === `${clave}-${id}`} disabled={Boolean(retirando)}
        onClick={() => retirar(clave, id, nombre)} aria-label={t('favoritos.retirarDe', { nombre })}>
        {t('favoritos.retirar')}
      </Boton>
    )
  }

  const lista = favoritos?.[tipo] ?? []

  return (
    <LayoutTienda>
      <main className="contenedor mis-pedidos">
        <h1 className="mis-pedidos__titulo">{t('misPedidos.miCuenta')}</h1>
        <div className="mis-pedidos__cuerpo">
          <LateralCuenta activo="favoritos" />
          <section className="mis-pedidos__contenido" aria-labelledby="titulo-favoritos" aria-busy={!favoritos && !error}>
            <h2 id="titulo-favoritos" className="mis-favoritos__titulo">{t('favoritos.titulo')}</h2>
            <p className="body-s mis-favoritos__nota">{t('favoritos.nota')}</p>
            {error && (
              <EstadoVacio titulo={t('favoritos.error')} descripcion={t('comun.errorConexion')}>
                <Boton tamano="M" onClick={recargar}>{t('carrito.reintentar')}</Boton>
              </EstadoVacio>
            )}
            {!error && !favoritos && <p className="visually-hidden">{t('comun.cargando')}</p>}
            {favoritos && (
              <>
                <Pestanas
                  etiqueta={t('favoritos.titulo')}
                  valor={tipo}
                  onCambiar={setTipo}
                  opciones={TIPOS.map((k) => ({ valor: k, etiqueta: t(`favoritos.pestana.${k}`, { count: favoritos[k].length }) }))}
                />
                {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
                <div role="tabpanel" aria-label={t(`favoritos.tipo.${tipo}`)}>
                  {lista.length === 0 ? (
                    <EstadoVacio titulo={t(`favoritos.vacio.${tipo}`)} descripcion={t('favoritos.vacioTexto')}>
                      <Boton tamano="M" to={tipo === 'productos' ? RUTAS.buscar : RUTAS.comunidades}>{t('misPedidos.explorar')}</Boton>
                    </EstadoVacio>
                  ) : (
                    <ul className="mis-favoritos__lista" aria-label={t(`favoritos.tipo.${tipo}`)}>
                      {tipo === 'productos' && lista.map((p) => (
                        <li key={p.id} className="mis-favoritos__item">
                          {p.oferta ? <TarjetaProducto producto={p.oferta} /> : <NoPublicado nombre={p.nombre} />}
                          {botonRetirar('productos', p.id, p.nombre)}
                        </li>
                      ))}
                      {tipo === 'productores' && lista.map((p) => (
                        <li key={p.id} className="mis-favoritos__item">
                          {p.tienda ? <TarjetaTienda tienda={p.tienda} /> : <NoPublicado nombre={p.nombre} />}
                          {botonRetirar('productores', p.id, p.nombre)}
                        </li>
                      ))}
                      {tipo === 'comunidades' && lista.map((c) => (
                        <li key={c.tiendaId} className="mis-favoritos__item mis-favoritos__comunidad">
                          <p className="mis-favoritos__nombre">{c.comunidad}</p>
                          {c.region && <p className="caption">{c.region}</p>}
                          {c.tiendas.length > 0 ? (
                            <ul className="mis-favoritos__tiendas" aria-label={t('favoritos.tiendasDe', { comunidad: c.comunidad })}>
                              {c.tiendas.map((tienda) => (
                                <li key={tienda.id}>
                                  <Enlace to={`/tiendas/${tienda.id}`} className="enlace-marca">{tienda.nombre}</Enlace>
                                </li>
                              ))}
                            </ul>
                          ) : (
                            <p className="caption">{t('favoritos.sinTiendas')}</p>
                          )}
                          {botonRetirar('comunidades', c.tiendaId, c.comunidad)}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </>
            )}
          </section>
        </div>
      </main>
    </LayoutTienda>
  )
}

function NoPublicado({ nombre }) {
  const { t } = useTranslation()
  return (
    <div className="mis-favoritos__no-publicado">
      <p className="mis-favoritos__nombre">{nombre}</p>
      <p className="caption">{t('favoritos.noPublicado')}</p>
    </div>
  )
}
