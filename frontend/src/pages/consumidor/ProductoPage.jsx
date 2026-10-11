import { useEffect, useState } from 'react'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoIa from '../../assets/icons/ia.svg'
import { obtenerProducto } from '../../api/catalogo.js'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import SelloOrigen from '../../components/SelloOrigen.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { capital, fecha, mesAnio, precio } from '../../formato.js'
import { RUTAS } from '../../router.js'
import AgregarAlCarrito from './AgregarAlCarrito.jsx'
import './ProductoPage.css'

/** CW-03: ficha publica del producto (CCPIP-53) con su historia de origen (CCPIP-152). */
export default function ProductoPage({ id }) {
  const [producto, setProducto] = useState(null)
  const [error, setError] = useState(null)
  const [foto, setFoto] = useState(0)
  const [modo, setModo] = useState('MINORISTA')

  useEffect(() => {
    let vigente = true
    setProducto(null)
    setError(null)
    setFoto(0)
    obtenerProducto(id)
      .then((p) => {
        if (!vigente) return
        setProducto(p)
        setModo(p.modalidad === 'MAYORISTA' ? 'MAYORISTA' : 'MINORISTA')
      })
      .catch((e) => vigente && setError(e))
    return () => {
      vigente = false
    }
  }, [id])

  if (error) {
    const noExiste = error.status === 404 || error.status === 400
    return (
      <LayoutTienda>
        <main className="contenedor ficha">
          <EstadoVacio
            titulo={noExiste ? 'Este producto no está disponible' : 'No pudimos cargar el producto'}
            descripcion={noExiste ? 'Puede que la comunidad lo haya retirado del catálogo.' : 'Revisa tu conexión e inténtalo otra vez.'}
          >
            <Boton tamano="M" to={RUTAS.buscar}>
              Volver al catálogo
            </Boton>
          </EstadoVacio>
        </main>
      </LayoutTienda>
    )
  }

  if (!producto) {
    return (
      <LayoutTienda>
        <main className="contenedor ficha" aria-busy="true">
          <p className="visually-hidden">Cargando producto…</p>
        </main>
      </LayoutTienda>
    )
  }

  const { tienda, historia, cosecha } = producto
  const imagenes = producto.imagenes ?? []
  const actual = imagenes[foto]
  const conMayorista = producto.modalidad !== 'MINORISTA'
  const conMinorista = producto.modalidad !== 'MAYORISTA'
  const unidad = producto.contenido ?? producto.unidad?.nombre?.toLowerCase()
  const partes = historia?.partes
  const pasos = partes
    ? [
        { titulo: 'La comunidad', texto: partes.comunidad },
        { titulo: 'El cultivo', texto: partes.cultivo },
        { titulo: 'El proceso', texto: partes.proceso },
      ].filter((p) => p.texto)
    : []

  return (
    <LayoutTienda>
      <main className="contenedor ficha">
        <nav className="migas" aria-label="Migas de pan">
          <Enlace to={RUTAS.catalogo}>Inicio</Enlace> /{' '}
          {producto.linea && <><Enlace to={`${RUTAS.buscar}?linea=${producto.linea.codigo}`}>{producto.linea.nombre}</Enlace> / </>}
          <span aria-current="page">{producto.nombre}</span>
        </nav>

        <div className="ficha__principal">
          <div className="ficha__galeria">
            <div className="ficha__foto">
              <ImagenProducto url={actual?.url} linea={producto.linea?.codigo} alt={producto.nombre} className="ficha__foto-img" />
              {imagenes.length > 1 && (
                <span className="ficha__contador">
                  {foto + 1} / {imagenes.length}
                </span>
              )}
            </div>
            {imagenes.length > 1 && (
              <ul className="ficha__miniaturas" aria-label="Fotos del producto">
                {imagenes.map((img, i) => (
                  <li key={img.id}>
                    <button type="button" className={`ficha__miniatura${i === foto ? ' ficha__miniatura--activa' : ''}`} onClick={() => setFoto(i)} aria-label={`Ver foto ${i + 1}`} aria-pressed={i === foto}>
                      <img src={img.miniatura} alt="" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <dl className="ficha__datos">
              <div>
                <dt>Origen</dt>
                <dd>{[tienda.localidad, tienda.region].filter(Boolean).join(', ')}</dd>
              </div>
              {cosecha?.produccion && (
                <div>
                  <dt>Cosecha</dt>
                  <dd>{capital(mesAnio(cosecha.produccion))}</dd>
                </div>
              )}
              {unidad && (
                <div>
                  <dt>Presentación</dt>
                  <dd>{capital(unidad)}</dd>
                </div>
              )}
              <div>
                <dt>Elaborado por</dt>
                <dd>{tienda.pueblo ? `Pueblo ${tienda.pueblo}` : tienda.comunidad}</dd>
              </div>
            </dl>
          </div>

          <div className="ficha__info">
            <p className="overline">{[producto.linea?.nombre, tienda.region].filter(Boolean).join(' · ')}</p>
            <h1 className="ficha__titulo">{producto.nombre}</h1>
            <p className="ficha__productor">
              <Icono src={iconoComunidad} tamano={20} />
              Hecho por{' '}
              <Enlace to={`/tiendas/${tienda.id}`} className="ficha__tienda">
                {tienda.nombre}
              </Enlace>
              {tienda.pueblo && <span className="caption">· pueblo {tienda.pueblo}</span>}
            </p>
            {tienda.origenVerificado && <SelloOrigen variante="compacto" />}

            {historia && (
              <blockquote className="ficha__cita">
                <p>“{historia.titulo ?? resumen(historia.texto)}”</p>
                <a href="#historia" className="enlace-marca">Leer su historia <span aria-hidden="true">↓</span></a>
              </blockquote>
            )}

            <div className="ficha__compra">
              <div className="ficha__precios" role={conMinorista && conMayorista ? 'radiogroup' : undefined} aria-label="Precio según modalidad">
                {conMinorista && (
                  <PrecioOpcion activo={modo === 'MINORISTA'} interactivo={conMayorista} onElegir={() => setModo('MINORISTA')} titulo="Precio minorista" monto={producto.precio} detalle={unidad ? `por ${unidad}` : 'por unidad'} />
                )}
                {conMayorista && (
                  <PrecioOpcion
                    activo={modo === 'MAYORISTA'}
                    interactivo={conMinorista}
                    onElegir={() => setModo('MAYORISTA')}
                    titulo="Precio mayorista"
                    monto={producto.precioMayorista}
                    detalle={`desde ${producto.cantidadMinimaMayorista} und`}
                  />
                )}
              </div>
              {producto.disponible ? (
                <span className="etiqueta-producto etiqueta-producto--marca">● Disponible</span>
              ) : (
                <span className="etiqueta-producto etiqueta-producto--agotado">Agotado por ahora</span>
              )}
              <p className="body-s">
                {producto.disponible
                  ? 'Se recoge en un punto de acopio de la Cámara.'
                  : 'Sin stock por ahora. Vuelve pronto: la comunidad repone por cosecha.'}
              </p>
              <AgregarAlCarrito key={producto.id} producto={producto} />
            </div>

            {producto.descripcion && <p className="body-m ficha__descripcion">{producto.descripcion}</p>}
          </div>
        </div>

        {historia && (
          <section id="historia" className="historia" aria-labelledby="titulo-historia">
            <ImagenProducto url={imagenes[1]?.url ?? imagenes[0]?.url} linea="COMUNIDAD" className="historia__img" />
            <div className="historia__texto">
              <p className="overline historia__overline">La historia detrás</p>
              <h2 id="titulo-historia" className="historia__titulo">{historia.titulo ?? 'Del bosque a tu mesa'}</h2>
              {pasos.length > 0 ? (
                <ol className="historia__pasos">
                  {pasos.map((p, i) => (
                    <li key={p.titulo} className="historia__paso">
                      <span className="historia__numero">{i + 1}</span>
                      <span>
                        <span className="historia__paso-titulo">{p.titulo}</span>
                        <span className="historia__paso-texto">{capital(p.texto)}</span>
                      </span>
                    </li>
                  ))}
                </ol>
              ) : null}
              <p className="historia__relato">{historia.texto}</p>
              <p className="historia__nota">
                {historia.asistida
                  ? 'Relato de la comunidad, redactado con apoyo de inteligencia artificial y aprobado por ella.'
                  : 'Relato escrito por la comunidad.'}
                {historia.asistida && <Icono src={iconoIa} tamano={14} className="historia__nota-icono" />}
              </p>
            </div>
          </section>
        )}

        <section className="seccion" aria-labelledby="titulo-confianza">
          <h2 id="titulo-confianza" className="seccion__titulo">Por qué puedes confiar en este producto</h2>
          <div className="ficha__confianza">
            {tienda.origenVerificado ? (
              <SelloOrigen variante="completo" />
            ) : (
              <p className="body-m">La Cámara aún está verificando el origen de esta comunidad.</p>
            )}
            {(cosecha?.produccion || cosecha?.consumirAntesDe) && (
              <div className="ficha__cosecha">
                <p className="overline">Sobre esta cosecha</p>
                <dl>
                  {cosecha.produccion && (
                    <div>
                      <dt>Cosecha</dt>
                      <dd>{capital(mesAnio(cosecha.produccion))}</dd>
                    </div>
                  )}
                  <div>
                    <dt>Origen</dt>
                    <dd>{[tienda.localidad, tienda.region].filter(Boolean).join(', ')}</dd>
                  </div>
                  {cosecha.consumirAntesDe && (
                    <div>
                      <dt>Consumir antes de</dt>
                      <dd>{fecha(cosecha.consumirAntesDe)}</dd>
                    </div>
                  )}
                </dl>
              </div>
            )}
          </div>
        </section>

        {conMayorista && (
          <section className="ficha__volumen" aria-labelledby="titulo-volumen">
            <span className="etiqueta-producto etiqueta-producto--info">Mayorista</span>
            <h2 id="titulo-volumen" className="heading-h2">Para compras por volumen</h2>
            <p className="body-s">Para tiendas, cafeterías y exportadores: la misma historia y el mismo origen verificado.</p>
            <dl>
              <div>
                <dt>Pedido mínimo mayorista</dt>
                <dd>{producto.cantidadMinimaMayorista} und</dd>
              </div>
              <div>
                <dt>Precio por unidad</dt>
                <dd>{precio(producto.precioMayorista)}</dd>
              </div>
            </dl>
          </section>
        )}

        {producto.relacionados?.length > 0 && (
          <section className="seccion" aria-labelledby="titulo-relacionados">
            <h2 id="titulo-relacionados" className="seccion__titulo">También de {producto.linea?.nombre?.toLowerCase()}</h2>
            <ul className="grilla-productos">
              {producto.relacionados.map((p) => (
                <li key={p.id}>
                  <TarjetaProducto producto={p} />
                </li>
              ))}
            </ul>
          </section>
        )}
      </main>
    </LayoutTienda>
  )
}

function PrecioOpcion({ activo, interactivo, onElegir, titulo, monto, detalle }) {
  const contenido = (
    <>
      <span className="caption">{titulo}</span>
      <strong className="ficha__monto">{monto != null ? precio(monto) : '—'}</strong>
      <span className="caption">{detalle}</span>
    </>
  )
  if (!interactivo) return <div className="ficha__precio ficha__precio--activo">{contenido}</div>
  return (
    <button type="button" role="radio" aria-checked={activo} className={`ficha__precio${activo ? ' ficha__precio--activo' : ''}`} onClick={onElegir}>
      {contenido}
    </button>
  )
}

/** Primera oracion de la historia para la cita destacada. */
function resumen(texto) {
  const corte = texto.search(/[.!?](\s|$)/)
  return corte > 0 && corte < 160 ? texto.slice(0, corte + 1) : `${texto.slice(0, 140).trim()}…`
}
