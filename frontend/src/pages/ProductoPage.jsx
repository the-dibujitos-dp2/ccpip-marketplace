import { useEffect, useState } from 'react'
import { obtenerProducto } from '../api/catalogo.js'
import iconoComunidad from '../assets/icons/comunidad.svg'
import Alerta from '../components/Alerta.jsx'
import BadgeDisponibilidad from '../components/BadgeDisponibilidad.jsx'
import Boton from '../components/Boton.jsx'
import Enlace from '../components/Enlace.jsx'
import EstadoVacio from '../components/EstadoVacio.jsx'
import ImagenProducto from '../components/ImagenProducto.jsx'
import SelloOrigen from '../components/SelloOrigen.jsx'
import { precio } from '../formato.js'
import { navigate, RUTAS } from '../router.js'
import './ProductoPage.css'

/** Ficha publica del producto (CCPIP-53) con su historia de origen (CCPIP-152). */
export default function ProductoPage({ id, user }) {
  const [producto, setProducto] = useState(null)
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)

  useEffect(() => {
    let vigente = true
    setProducto(null)
    setError(null)
    setAviso(null)
    obtenerProducto(id)
      .then((datos) => vigente && setProducto(datos))
      .catch((e) => vigente && setError(e))
    return () => {
      vigente = false
    }
  }, [id])

  if (error) {
    const noExiste = error.status === 404 || error.status === 400
    return (
      <main className="producto">
        <EstadoVacio
          titulo={noExiste ? 'Este producto no está disponible' : 'No pudimos cargar el producto'}
          descripcion={noExiste ? 'Puede que lo hayan retirado del catálogo.' : 'Revisa tu conexión e inténtalo otra vez.'}
        >
          <Boton tamano="M" onClick={() => navigate(RUTAS.catalogo)}>
            Volver al catálogo
          </Boton>
        </EstadoVacio>
      </main>
    )
  }

  if (!producto) return <p className="visually-hidden">Cargando producto…</p>

  const { productor } = producto

  function agregarAlCarrito() {
    if (!user) {
      navigate(`${RUTAS.ingresar}?volver=${encodeURIComponent(`/productos/${producto.id}`)}`)
      return
    }
    setAviso({ tono: 'info', titulo: 'El carrito estará disponible muy pronto', descripcion: 'Estamos terminando de prepararlo.' })
  }

  return (
    <main className="producto">
      <Enlace to={RUTAS.catalogo} className="producto__volver">
        ← Volver al catálogo
      </Enlace>

      <article className="producto__ficha">
        <ImagenProducto linea={producto.linea.codigo} className="producto__imagen" />

        <div className="producto__info">
          <p className="overline">{producto.linea.nombre}</p>
          <h1 className="display-l">{producto.nombre}</h1>
          <p className="producto__productor">
            <img src={iconoComunidad} width="20" height="20" alt="" />
            <Enlace to={`/productores/${productor.id}`}>{productor.comunidad}</Enlace>
            <span className="caption">· {productor.region}</span>
          </p>
          {productor.origenVerificado && <SelloOrigen variante="compacto" />}

          <p className="producto__precio">{precio(producto.precio)}</p>
          <BadgeDisponibilidad disponible={producto.disponible} />

          {producto.descripcion && <p className="body-l">{producto.descripcion}</p>}

          <dl className="producto__datos">
            <div>
              <dt>Línea</dt>
              <dd>{producto.linea.nombre}</dd>
            </div>
            <div>
              <dt>Unidad de medida</dt>
              <dd>{producto.unidad.nombre}</dd>
            </div>
          </dl>

          {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

          <div className="producto__acciones">
            <Boton tipo="acento" anchoCompleto disabled={!producto.disponible} onClick={agregarAlCarrito}>
              Agregar al carrito
            </Boton>
            {!producto.disponible && (
              <p className="caption">Sin stock por ahora. Puedes volver a revisarlo más tarde.</p>
            )}
          </div>
        </div>
      </article>

      {producto.historiaOrigen && (
        <section className="producto__historia" aria-labelledby="titulo-historia">
          <div className="producto__historia-texto">
            <p className="overline">Historia de origen</p>
            <h2 id="titulo-historia" className="heading-h2">La historia detrás de este producto</h2>
            <p className="body-l">{producto.historiaOrigen}</p>
            <p className="caption">
              {productor.comunidad} · {productor.region}
            </p>
          </div>
          {productor.origenVerificado && <SelloOrigen variante="completo" />}
        </section>
      )}
    </main>
  )
}
