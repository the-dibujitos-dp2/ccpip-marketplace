import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoIa from '../../assets/icons/ia.svg'
import { obtenerProducto } from '../../api/catalogo.js'
import AvisoTraduccion from '../../components/AvisoTraduccion.jsx'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import SelloOrigen from '../../components/SelloOrigen.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { capital, fecha, mesAnio, precio, primeraOracion } from '../../formato.js'
import { RUTAS } from '../../router.js'
import { idiomaSegun, textoSegun } from '../../traduccion.js'
import { resolverTarifa } from '../../tarifa.js'
import AgregarAlCarrito from './AgregarAlCarrito.jsx'
import MeInteresa from './MeInteresa.jsx'
import OfertasMaestro from './OfertasMaestro.jsx'
import './ProductoPage.css'

// CCPIP-171: campos traducibles de cada seccion, con los nombres que usa el catalogo
const CAMPOS_FICHA = ['nombre', 'descripcion']
const CAMPOS_HISTORIA = ['historia.titulo', 'historia.texto', 'historia.comunidad', 'historia.cultivo', 'historia.proceso', 'historia.significado']

/**
 * CW-03: ficha publica del producto (CCPIP-53) con su historia de origen (CCPIP-152). En ingles,
 * cada seccion traducida lleva su etiqueta y "Ver original" (CCPIP-171, CW-03-EN).
 */
export default function ProductoPage({ id }) {
  const { t } = useTranslation()
  const [producto, setProducto] = useState(null)
  const [error, setError] = useState(null)
  const [foto, setFoto] = useState(0)
  const [modo, setModo] = useState('MINORISTA')
  const [originalFicha, setOriginalFicha] = useState(false)
  const [originalHistoria, setOriginalHistoria] = useState(false)
  // CCPIP-134: la cantidad del panel de compra decide la tarifa, por eso se comparte con las tarjetas de precio
  const [cantidad, setCantidad] = useState('1')

  useEffect(() => {
    let vigente = true
    setProducto(null)
    setError(null)
    setFoto(0)
    setOriginalFicha(false)
    setOriginalHistoria(false)
    obtenerProducto(id)
      .then((p) => {
        if (!vigente) return
        setProducto(p)
        setModo(p.modalidad === 'MAYORISTA' ? 'MAYORISTA' : 'MINORISTA')
        // Una oferta solo mayorista arranca en su minimo: menos no se puede comprar
        setCantidad(String(p.modalidad === 'MAYORISTA' ? (p.cantidadMinimaMayorista ?? 1) : 1))
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
            titulo={noExiste ? t('producto.noDisponible') : t('producto.error')}
            descripcion={noExiste ? t('producto.retirado') : t('comun.errorConexion')}
          >
            <Boton tamano="M" to={RUTAS.buscar}>
              {t('producto.volverCatalogo')}
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
          <p className="visually-hidden">{t('producto.cargando')}</p>
        </main>
      </LayoutTienda>
    )
  }

  const { tienda, historia: historiaServida, cosecha, traducciones } = producto
  // "Ver original" cambia cada texto traducido de la seccion por su original
  const ficha = (campo, valor) => textoSegun(traducciones, campo, valor, originalFicha)
  const deHistoria = (campo, valor) => textoSegun(traducciones, `historia.${campo}`, valor, originalHistoria)
  const historia = historiaServida && {
    ...historiaServida,
    titulo: deHistoria('titulo', historiaServida.titulo),
    texto: deHistoria('texto', historiaServida.texto),
    partes: historiaServida.partes && Object.fromEntries(Object.entries(historiaServida.partes).map(([k, v]) => [k, deHistoria(k, v)])),
  }
  const idiomaHistoria = idiomaSegun(traducciones, 'historia.texto', originalHistoria)
  const imagenes = producto.imagenes ?? []
  const actual = imagenes[foto]
  const conMayorista = producto.modalidad !== 'MINORISTA'
  const conMinorista = producto.modalidad !== 'MAYORISTA'
  const unidad = producto.contenido ?? producto.unidad?.nombre?.toLowerCase()
  // CCPIP-134 (Figma CW-03c): desde el minimo el mayorista se aplica solo, aunque se haya elegido el minorista
  const activa = resolverTarifa(producto, cantidad).tarifa === 'MAYORISTA' ? 'MAYORISTA' : modo
  function elegirMinorista() {
    setModo('MINORISTA')
    // Volver al minorista desde el mayorista aplicado es volver a comprar por unidad
    if (activa === 'MAYORISTA') setCantidad('1')
  }
  const partes = historia?.partes
  // CCPIP-42: las demas comunidades que ofrecen el mismo producto del catalogo maestro
  const otrasOfertas = producto.maestro?.ofertas?.filter((o) => o.id !== producto.id) ?? []
  const pasos = partes
    ? [
        { titulo: t('producto.historia.comunidad'), texto: partes.comunidad },
        { titulo: t('producto.historia.cultivo'), texto: partes.cultivo },
        { titulo: t('producto.historia.proceso'), texto: partes.proceso },
      ].filter((p) => p.texto)
    : []

  return (
    <LayoutTienda>
      <main className="contenedor ficha">
        <nav className="migas" aria-label={t('comun.migas')}>
          <Enlace to={RUTAS.catalogo}>{t('comun.inicio')}</Enlace> /{' '}
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
              <ul className="ficha__miniaturas" aria-label={t('producto.fotos')}>
                {imagenes.map((img, i) => (
                  <li key={img.id}>
                    <button type="button" className={`ficha__miniatura${i === foto ? ' ficha__miniatura--activa' : ''}`} onClick={() => setFoto(i)} aria-label={t('producto.verFoto', { numero: i + 1 })} aria-pressed={i === foto}>
                      <img src={img.miniatura} alt="" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <dl className="ficha__datos">
              <div>
                <dt>{t('producto.origen')}</dt>
                <dd>{[tienda.localidad, tienda.region].filter(Boolean).join(', ')}</dd>
              </div>
              {cosecha?.produccion && (
                <div>
                  <dt>{t('producto.cosecha')}</dt>
                  <dd>{capital(mesAnio(cosecha.produccion))}</dd>
                </div>
              )}
              {unidad && (
                <div>
                  <dt>{t('producto.presentacion')}</dt>
                  <dd>{capital(unidad)}</dd>
                </div>
              )}
              <div>
                <dt>{t('producto.elaboradoPor')}</dt>
                <dd>{tienda.pueblo ? t('tienda.pueblo', { pueblo: tienda.pueblo }) : tienda.comunidad}</dd>
              </div>
            </dl>
          </div>

          <div className="ficha__info">
            <p className="overline">{[producto.linea?.nombre, tienda.region].filter(Boolean).join(' · ')}</p>
            <h1 className="ficha__titulo" lang={idiomaSegun(traducciones, 'nombre', originalFicha)}>{ficha('nombre', producto.nombre)}</h1>
            <AvisoTraduccion traducciones={traducciones} campos={CAMPOS_FICHA} verOriginal={originalFicha} onAlternar={() => setOriginalFicha((v) => !v)} />
            <p className="ficha__productor">
              <Icono src={iconoComunidad} tamano={20} />
              {t('producto.hechoPor')}{' '}
              <Enlace to={`/tiendas/${tienda.id}`} className="ficha__tienda">
                {tienda.nombre}
              </Enlace>
              {tienda.pueblo && <span className="caption">{t('producto.puebloCorto', { pueblo: tienda.pueblo })}</span>}
            </p>
            {tienda.origenVerificado && <SelloOrigen variante="compacto" />}

            {historia && (
              <blockquote className="ficha__cita" lang={idiomaHistoria}>
                <p>“{historia.titulo ?? primeraOracion(historia.texto)}”</p>
                <a href="#historia" className="enlace-marca">{t('producto.leerHistoria')} <span aria-hidden="true">↓</span></a>
              </blockquote>
            )}

            <div className="ficha__compra">
              <div className="ficha__precios" role={conMinorista && conMayorista ? 'radiogroup' : undefined} aria-label={t('producto.precioSegunModalidad')}>
                {conMinorista && (
                  <PrecioOpcion activo={activa === 'MINORISTA'} interactivo={conMayorista} onElegir={elegirMinorista} titulo={t('producto.precioMinorista')} monto={producto.precio} detalle={unidad ? t('producto.porUnidadDe', { unidad }) : t('producto.porUnidad')} />
                )}
                {conMayorista && (
                  <PrecioOpcion
                    activo={activa === 'MAYORISTA'}
                    interactivo={conMinorista}
                    onElegir={() => setModo('MAYORISTA')}
                    titulo={t('producto.precioMayorista')}
                    monto={producto.precioMayorista}
                    detalle={t('producto.desdeUnidades', { count: producto.cantidadMinimaMayorista })}
                  />
                )}
              </div>
              {/* CCPIP-269 (CW-03c "Venta pausada"): sin cuenta bancaria activa o autorizacion vigente, se ve pero no se compra */}
              {producto.comprable === false ? (
                <span className="etiqueta-producto etiqueta-producto--aviso">● {t('producto.estado.pausada')}</span>
              ) : producto.disponible ? (
                <span className="etiqueta-producto etiqueta-producto--marca">● {t('producto.estado.disponible')}</span>
              ) : (
                <span className="etiqueta-producto etiqueta-producto--agotado">{t('producto.estado.agotado')}</span>
              )}
              <p className="body-s">
                {producto.comprable === false
                  ? t('producto.estado.pausadaTexto', { nombre: tienda.nombre })
                  : producto.disponible
                    ? t('producto.estado.disponibleTexto')
                    : t('producto.estado.agotadoTexto')}
              </p>
              <AgregarAlCarrito key={producto.id} producto={producto} cantidad={cantidad} onCantidad={setCantidad} modo={activa} />
              <MeInteresa key={`interes-${producto.id}`} producto={producto} />
            </div>

            {producto.descripcion && (
              <p className="body-m ficha__descripcion" lang={idiomaSegun(traducciones, 'descripcion', originalFicha)}>
                {ficha('descripcion', producto.descripcion)}
              </p>
            )}
          </div>
        </div>

        {historia && (
          <section id="historia" className="historia" aria-labelledby="titulo-historia">
            <ImagenProducto url={imagenes[1]?.url ?? imagenes[0]?.url} linea="COMUNIDAD" className="historia__img" />
            <div className="historia__texto">
              <p className="overline historia__overline">{t('producto.historia.overline')}</p>
              <h2 id="titulo-historia" className="historia__titulo">{historia.titulo ?? t('producto.historia.titulo')}</h2>
              <AvisoTraduccion traducciones={traducciones} campos={CAMPOS_HISTORIA} verOriginal={originalHistoria} onAlternar={() => setOriginalHistoria((v) => !v)} />
              {pasos.length > 0 ? (
                <ol className="historia__pasos" lang={idiomaHistoria}>
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
              <p className="historia__relato" lang={idiomaHistoria}>{historia.texto}</p>
              <p className="historia__nota">
                {historia.asistida
                  ? t('producto.historia.asistida')
                  : t('producto.historia.propia')}
                {historia.asistida && <Icono src={iconoIa} tamano={14} className="historia__nota-icono" />}
              </p>
            </div>
          </section>
        )}

        <section className="seccion" aria-labelledby="titulo-confianza">
          <h2 id="titulo-confianza" className="seccion__titulo">{t('producto.confianza')}</h2>
          <div className="ficha__confianza">
            {tienda.origenVerificado ? (
              <SelloOrigen variante="completo" />
            ) : (
              <p className="body-m">{t('producto.verificando')}</p>
            )}
            {(cosecha?.produccion || cosecha?.consumirAntesDe) && (
              <div className="ficha__cosecha">
                <p className="overline">{t('producto.sobreCosecha')}</p>
                <dl>
                  {cosecha.produccion && (
                    <div>
                      <dt>{t('producto.cosecha')}</dt>
                      <dd>{capital(mesAnio(cosecha.produccion))}</dd>
                    </div>
                  )}
                  <div>
                    <dt>{t('producto.origen')}</dt>
                    <dd>{[tienda.localidad, tienda.region].filter(Boolean).join(', ')}</dd>
                  </div>
                  {cosecha.consumirAntesDe && (
                    <div>
                      <dt>{t('producto.consumirAntes')}</dt>
                      <dd>{fecha(cosecha.consumirAntesDe)}</dd>
                    </div>
                  )}
                </dl>
              </div>
            )}
          </div>
        </section>

        {/* Figma CW-03 "Volumen y otras ofertas": la compra por volumen y, al lado, los demas productores (CCPIP-42) */}
        {(conMayorista || otrasOfertas.length > 0) && (
          <div className="ficha__volumen-ofertas">
            {conMayorista && (
              <section className="ficha__volumen" aria-labelledby="titulo-volumen">
                <span className="etiqueta-producto etiqueta-producto--info">{t('modalidad.MAYORISTA')}</span>
                <h2 id="titulo-volumen" className="heading-h2">{t('producto.volumen.titulo')}</h2>
                <p className="body-s">{t('producto.volumen.texto')}</p>
                <dl>
                  <div>
                    <dt>{t('producto.volumen.minimo')}</dt>
                    <dd>{t('producto.unidades', { count: producto.cantidadMinimaMayorista })}</dd>
                  </div>
                  <div>
                    <dt>{t('producto.volumen.precioUnidad')}</dt>
                    <dd>{precio(producto.precioMayorista)}</dd>
                  </div>
                </dl>
              </section>
            )}
            {otrasOfertas.length > 0 && <OfertasMaestro maestro={producto.maestro} ofertas={otrasOfertas} />}
          </div>
        )}

        {producto.relacionados?.length > 0 && (
          <section className="seccion" aria-labelledby="titulo-relacionados">
            <h2 id="titulo-relacionados" className="seccion__titulo">{t('producto.tambienDe', { linea: producto.linea?.nombre?.toLowerCase() })}</h2>
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
