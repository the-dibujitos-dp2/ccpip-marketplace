import { useEffect, useState } from 'react'
import iconoEstrella from '../../assets/icons/estrella.svg'
import { obtenerTienda } from '../../api/catalogo.js'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import SelloOrigen from '../../components/SelloOrigen.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'
import './TiendaPage.css'

/** CW-04: tienda de la comunidad (CCPIP-154, CCPIP-56). Publica, sin sesion. */
export default function TiendaPage({ id }) {
  const [tienda, setTienda] = useState(null)
  const [error, setError] = useState(null)
  const [linea, setLinea] = useState('TODOS')
  const [copiado, setCopiado] = useState(false)

  useEffect(() => {
    let vigente = true
    setTienda(null)
    setError(null)
    setLinea('TODOS')
    obtenerTienda(id)
      .then((t) => vigente && setTienda(t))
      .catch((e) => vigente && setError(e))
    return () => {
      vigente = false
    }
  }, [id])

  if (error) {
    return (
      <LayoutTienda>
        <main className="contenedor tienda-publica">
          <EstadoVacio
            titulo={error.status === 404 ? 'Esta tienda no está disponible' : 'No pudimos cargar la tienda'}
            descripcion={error.status === 404 ? 'Puede que la comunidad aún esté preparándola.' : 'Revisa tu conexión e inténtalo otra vez.'}
          >
            <Boton tamano="M" to={RUTAS.comunidades}>
              Ver comunidades
            </Boton>
          </EstadoVacio>
        </main>
      </LayoutTienda>
    )
  }

  if (!tienda) {
    return (
      <LayoutTienda>
        <main aria-busy="true" />
      </LayoutTienda>
    )
  }

  const productos = linea === 'TODOS' ? tienda.productos : tienda.productos.filter((p) => p.linea?.codigo === linea || p.lineas?.includes(linea))
  const destacados = tienda.productos.filter((p) => tienda.destacados?.includes(p.id))

  async function compartir() {
    const url = window.location.href
    try {
      if (navigator.share) await navigator.share({ title: tienda.nombre, url })
      else await navigator.clipboard.writeText(url)
      setCopiado(true)
    } catch {
      // El usuario cancelo
    }
  }

  return (
    <LayoutTienda>
      <main className="tienda-publica">
        <div className="tienda-publica__portada">
          <ImagenProducto url={tienda.portada} linea="PORTADA" alt={`Portada de ${tienda.nombre}`} className="tienda-publica__portada-img" />
        </div>
        <div className="contenedor tienda-publica__contenido">
          <header className="tienda-publica__cabecera">
            <span className="tienda-publica__avatar">
              <ImagenProducto linea="COMUNIDAD" className="tienda-publica__avatar-img" />
            </span>
            <div className="tienda-publica__identidad">
              {tienda.origenVerificado && <SelloOrigen variante="compacto" />}
              <h1 className="display-l">{tienda.nombre}</h1>
              <p className="body-s">
                {[tienda.pueblo && `Pueblo ${tienda.pueblo}`, [tienda.localidad, tienda.region].filter(Boolean).join(', '), tienda.socios && `${tienda.socios} socias y socios`]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
            </div>
            <dl className="tienda-publica__cifras">
              <div>
                <dt>productos</dt>
                <dd>{tienda.productos.length}</dd>
              </div>
              {tienda.desde && (
                <div>
                  <dt>en la plataforma</dt>
                  <dd>{tienda.desde}</dd>
                </div>
              )}
            </dl>
            <Boton tipo="secundario" tamano="M" onClick={compartir}>
              {copiado ? 'Enlace copiado' : 'Compartir'}
            </Boton>
          </header>

          <div className={`tienda-publica__historia${tienda.galeria?.length > 0 ? ' tienda-publica__historia--galeria' : ''}`}>
            <section aria-labelledby="titulo-nuestra-historia">
              <h2 id="titulo-nuestra-historia" className="seccion__titulo">Nuestra historia</h2>
              {tienda.cita && <p className="tienda-publica__cita">“{tienda.cita}”</p>}
              {tienda.historia && <p className="body-m tienda-publica__relato">{tienda.historia}</p>}
              {tienda.descripcion && !tienda.historia && <p className="body-m">{tienda.descripcion}</p>}
            </section>
            {tienda.galeria?.length > 0 && (
              <section aria-labelledby="titulo-galeria">
                <h2 id="titulo-galeria" className="heading-h4">Galería</h2>
                <ul className="tienda-publica__galeria">
                  {tienda.galeria.slice(0, 6).map((g) => (
                    <li key={g.id}>
                      <a href={g.url} target="_blank" rel="noreferrer">
                        <img src={g.miniatura} alt={`Foto de ${tienda.nombre}`} loading="lazy" />
                      </a>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </div>

          {tienda.lineas?.length > 0 && (
            <Pestanas
              etiqueta="Productos por línea"
              valor={linea}
              onCambiar={setLinea}
              opciones={[
                { valor: 'TODOS', etiqueta: `Todos (${tienda.productos.length})` },
                ...tienda.lineas.map((l) => ({ valor: l.codigo, etiqueta: `${l.nombre} (${l.total})` })),
              ]}
            />
          )}

          {linea === 'TODOS' && destacados.length > 0 && (
            <p className="caption tienda-publica__destacados">
              <Icono src={iconoEstrella} tamano={16} />
              Destacados por la comunidad · elegidos por {tienda.nombre}
            </p>
          )}

          {tienda.productos.length === 0 ? (
            <EstadoVacio titulo="Aún no hay productos publicados" descripcion="Esta comunidad está preparando su catálogo. Vuelve pronto." />
          ) : (
            <ul className="grilla-productos" aria-label={`Productos de ${tienda.nombre}`}>
              {productos.map((p) => (
                <li key={p.id}>
                  <TarjetaProducto producto={p} />
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </LayoutTienda>
  )
}
