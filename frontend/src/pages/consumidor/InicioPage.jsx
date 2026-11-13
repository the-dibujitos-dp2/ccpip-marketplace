import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoArbol from '../../assets/icons/arbol.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoEscudo from '../../assets/icons/sello-completo.svg'
import iconoSinQuimicos from '../../assets/icons/sin-quimicos.svg'
import { obtenerInicio, obtenerMasVendidos, obtenerNovedades } from '../../api/catalogo.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Enlace from '../../components/Enlace.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import { FOTOGRAFIAS_REFERENCIA } from '../../components/fotografiasEditoriales.js'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import TarjetaTienda from '../../components/TarjetaTienda.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RoccoDice } from '../../components/Rocco.jsx'
import { RUTAS } from '../../router.js'
import './InicioPage.css'
import fotoArtesana from '../../assets/editorial/artesana.jpg'


const VALORES = [
  { icono: iconoArbol, clave: 'deforestacion' },
  { icono: iconoSinQuimicos, clave: 'pesticidas' },
  { icono: iconoComunidad, clave: 'comunidades' },
  { icono: iconoEscudo, clave: 'verificado' },
]

/** CCPIP-339 y CCPIP-338: periodos que el visitante puede elegir para los mas vendidos y las novedades. */
const PERIODOS = ['ULTIMOS_30_DIAS', 'ULTIMOS_3_MESES', 'ESTE_ANIO']

/** CW-01: inicio de la tienda web. */
export default function InicioPage() {
  const { t, i18n } = useTranslation()
  const { t: te } = useTranslation('editorial')
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(false)
  const [pestana, setPestana] = useState('destacados')
  const [periodo, setPeriodo] = useState(PERIODOS[0])

  useEffect(() => {
    obtenerInicio().then(setDatos).catch(() => setError(true))
  }, [])

  const destacados = datos?.destacados ?? []
  const relato = datos?.tiendas?.[0]

  return (
    <LayoutTienda>
      <main className="inicio-tienda">
        <section className="hero" aria-labelledby="titulo-hero">
          <img className="hero__fotografia" src={fotoArtesana} alt={te('fotoArtesana')} />
          <div className="hero__texto">
            <h1 id="titulo-hero" className="hero__titulo">{te('hero.linea1')}<br />{te('hero.linea2')} <em>{te('hero.origen')}</em></h1>
            <p className="hero__descripcion">{te('hero.descripcion')}</p>
            <div className="hero__acciones">
              <Boton tipo="acento" to={RUTAS.buscar}>{te('hero.explorar')}</Boton>
              <Enlace to={RUTAS.comunidades} className="hero__enlace">{te('hero.comunidades')}</Enlace>
            </div>
            {/* CCPIP-513: Rocco da la bienvenida */}
            <RoccoDice variante="saluda" className="hero__rocco">{t('rocco.saludo')}</RoccoDice>
            <a className="hero__credito" href="https://www.infobae.com/peru/2024/11/27/cacao-cafe-arte-textil-y-mas-emprendedores-indigenas-ofreceran-mas-de-100-productos-libres-de-deforestacion/" target="_blank" rel="noopener noreferrer">
              {te('pie.creditos')} · Infobae · 2024-11-27
            </a>
          </div>
        </section>
        <div className="inicio-origen">
          <p className="inicio-origen__titulo">{te('origen.titulo')}</p>
          <ul className="valores" aria-label={t('inicio.valores.etiqueta')}>
            {VALORES.map((v) => (
              <li key={v.clave} className="valor">
                <span className="valor__icono">
                  <img src={v.icono} width="20" height="20" alt="" />
                </span>
                <span>
                  <span className="label-m">{t(`inicio.valores.${v.clave}.titulo`)}</span>
                  <span className="caption valor__texto">{t(`inicio.valores.${v.clave}.texto`)}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
        <section className="seccion inicio-categorias" aria-labelledby="inicio-categorias-titulo">
          <div className="seccion__encabezado">
            <h2 id="inicio-categorias-titulo" className="seccion__titulo">{te('categorias.titulo')}</h2>
          </div>
          <div className="inicio-categorias__grid">
            {(datos?.lineas ?? []).slice(0, 3).map((item) => (
              <Enlace key={item.codigo} to={`${RUTAS.buscar}?linea=${item.codigo}`} className="inicio-categoria">
                {datos.fotosLineas?.[item.codigo] || Object.hasOwn(FOTOGRAFIAS_REFERENCIA, item.codigo)
                  ? <ImagenProducto url={datos.fotosLineas?.[item.codigo]} linea={item.codigo} className="inicio-categoria__imagen" />
                  : <span className="inicio-categoria__sin-foto" aria-hidden="true" />}
                <span className="inicio-categoria__texto">
                  <strong>{item.nombre}</strong>
                </span>
                <span className="inicio-categoria__flecha" aria-hidden="true">→</span>
              </Enlace>
            ))}
          </div>
        </section>

        {/* El relato sale de la primera tienda de la API; sin cita propia no se inventa una */}
        {relato && (
          <section className="inicio-relato" aria-labelledby="inicio-relato-titulo">
            <div className="inicio-relato__foto">
              <img
                src={relato.portada || fotoArtesana}
                alt={relato.portada ? te('relato.fotoTienda', { nombre: relato.nombre }) : te('fotoArtesana')}
              />
            </div>
            <div className="inicio-relato__texto">
              <h2 id="inicio-relato-titulo">{te('relato.titulo')}</h2>
              {relato.cita ? <blockquote>“{relato.cita}”</blockquote> : <p>{te('relato.sinCita')}</p>}
              <p className="inicio-relato__nombre">
                {relato.nombre}
                {relato.pueblo && <small>{[relato.pueblo, relato.region].filter(Boolean).join(' · ')}</small>}
              </p>
              <Boton tipo="acento" to={`/tiendas/${relato.id}`}>{te('relato.conocer')}</Boton>
            </div>
          </section>
        )}

        {error && <Alerta tono="error" titulo={t('inicio.error')} descripcion={t('comun.errorConexion')} />}
        {!datos && !error && <Cargando />}

        {datos?.tiendas.length > 0 && (
          <section className="seccion inicio-comunidades" aria-labelledby="titulo-comunidades">
            <div className="seccion__encabezado">
              <h2 id="titulo-comunidades" className="seccion__titulo">{t('inicio.comunidades')}</h2>
              <Enlace to={RUTAS.comunidades} className="enlace-marca">{t('comun.verTodas')}</Enlace>
            </div>
            <div className="inicio-tienda__tiendas">
              {datos.tiendas.map((t) => (
                <TarjetaTienda key={t.id} tienda={t} />
              ))}
            </div>
          </section>
        )}

        {datos?.lineas.length > 0 && (
          <section className="seccion inicio-lineas" aria-labelledby="titulo-lineas">
            <div className="seccion__encabezado">
              <h2 id="titulo-lineas" className="seccion__titulo">{t('inicio.lineas')}</h2>
              <Enlace to={RUTAS.buscar} className="enlace-marca">{t('comun.verTodas')}</Enlace>
            </div>
            <ul className="lineas-tienda">
              {datos.lineas.map((l) => (
                <li key={l.codigo}>
                  <Enlace to={`${RUTAS.buscar}?linea=${l.codigo}`} className="linea-tienda">
                    <ImagenProducto url={datos.fotosLineas?.[l.codigo]} linea={l.codigo} className="linea-tienda__img" />
                    <span className="label-m">{l.nombre}</span>
                    <span className="caption">{t('comun.producto', { count: l.total })}</span>
                  </Enlace>
                </li>
              ))}
            </ul>
          </section>
        )}

        {datos && (
          <section className="seccion inicio-ofertas" aria-labelledby="titulo-descubrir">
            <div className="seccion__encabezado">
              <div>
                <h2 id="titulo-descubrir" className="seccion__titulo">{t('inicio.descubrir.titulo')}</h2>
                <p className="body-s">{t('inicio.descubrir.bajada')}</p>
              </div>
              <Boton tipo="secundario" tamano="M" to={RUTAS.buscar}>
                {t('inicio.descubrir.verTodo')}
              </Boton>
            </div>
            <Pestanas
              etiqueta={t('inicio.descubrir.titulo')}
              valor={pestana}
              onCambiar={setPestana}
              opciones={[
                { valor: 'destacados', etiqueta: t('inicio.descubrir.destacados') },
                { valor: 'novedades', etiqueta: t('inicio.descubrir.novedades') },
                { valor: 'masVendidos', etiqueta: t('inicio.descubrir.masVendidos') },
              ]}
            />
            {pestana === 'destacados' && (
              <>
                {destacados.length === 0 && <p className="body-m">{t('inicio.descubrir.sinDestacados')}</p>}
                {destacados.length > 0 && (
                  <ul className="grilla-productos" aria-label={t('inicio.descubrir.destacados')}>
                    {destacados.map((p) => (
                      <li key={p.id}>
                        <TarjetaProducto producto={p} />
                      </li>
                    ))}
                  </ul>
                )}
                {/* CCPIP-57: la regla de los destacados, a la vista */}
                <p className="caption">{t('inicio.descubrir.notaDestacados')}</p>
              </>
            )}
            {pestana === 'novedades' && (
              <PorPeriodo
                obtener={obtenerNovedades}
                etiqueta={t('inicio.descubrir.novedades')}
                vacio={t('inicio.descubrir.sinNovedades')}
                nota={t('inicio.descubrir.notaNovedades')}
                periodo={periodo}
                onPeriodo={setPeriodo}
                idioma={i18n.resolvedLanguage}
              />
            )}
            {pestana === 'masVendidos' && (
              <PorPeriodo
                obtener={obtenerMasVendidos}
                etiqueta={t('inicio.descubrir.masVendidos')}
                vacio={t('inicio.descubrir.sinVentas')}
                nota={t('inicio.descubrir.notaMasVendidos')}
                conPuesto
                periodo={periodo}
                onPeriodo={setPeriodo}
                idioma={i18n.resolvedLanguage}
              />
            )}
          </section>
        )}

        <section className="mayorista" aria-labelledby="titulo-mayorista">
          <div className="mayorista__texto">
            <span className="etiqueta-producto etiqueta-producto--info">{t('modalidad.MAYORISTA')}</span>
            <h2 id="titulo-mayorista" className="seccion__titulo">{t('inicio.mayorista.titulo')}</h2>
            <p className="body-m">{t('inicio.mayorista.texto')}</p>
            <Boton to={`${RUTAS.buscar}?modalidad=MAYORISTA`}>{t('inicio.mayorista.ver')}</Boton>
          </div>
          <ImagenProducto linea="CAFE" className="mayorista__img" />
        </section>
      </main>
    </LayoutTienda>
  )
}

/**
 * Productos del periodo elegido, con su productor, que se consultan solo al abrir su pestaña.
 * CCPIP-339 (RF-229): los mas vendidos, con el puesto y sin unidades ni montos. CCPIP-338 (RF-228): lo
 * publicado recientemente. Si el periodo no tiene productos lo dice en vez de rellenar con otros.
 */
function PorPeriodo({ obtener, etiqueta, vacio, nota, conPuesto = false, periodo, onPeriodo, idioma }) {
  const { t } = useTranslation()
  const [respuesta, setRespuesta] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let vigente = true
    setError(false)
    obtener(periodo)
      .then((r) => { if (vigente) setRespuesta(r) })
      .catch(() => { if (vigente) setError(true) })
    return () => { vigente = false }
  }, [obtener, periodo, idioma])

  const actual = respuesta?.periodo === periodo ? respuesta : null
  return (
    <div className="mas-vendidos">
      <Campo
        className="mas-vendidos__periodo"
        etiqueta={t('inicio.descubrir.periodo')}
        tipo="selector"
        placeholder={false}
        value={periodo}
        onChange={(e) => onPeriodo(e.target.value)}
        opciones={PERIODOS.map((p) => ({ valor: p, etiqueta: t(`inicio.descubrir.periodos.${p}`) }))}
      />
      {error && <Alerta tono="error" titulo={t('inicio.error')} descripcion={t('comun.errorConexion')} />}
      {!error && !actual && <Cargando />}
      {actual && actual.productos.length === 0 && <p className="body-m">{vacio}</p>}
      {actual && actual.productos.length > 0 && (
        <ul className="grilla-productos" aria-label={etiqueta}>
          {actual.productos.map((p, i) => (
            <li key={p.id}>
              <TarjetaProducto producto={p} puesto={conPuesto ? i + 1 : undefined} />
            </li>
          ))}
        </ul>
      )}
      <p className="caption">{nota}</p>
    </div>
  )
}
