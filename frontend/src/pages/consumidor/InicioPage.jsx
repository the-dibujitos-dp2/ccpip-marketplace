import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoArbol from '../../assets/icons/arbol.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoEscudo from '../../assets/icons/sello-completo.svg'
import iconoSinQuimicos from '../../assets/icons/sin-quimicos.svg'
import { obtenerInicio, obtenerMasVendidos } from '../../api/catalogo.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Enlace from '../../components/Enlace.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import TarjetaTienda from '../../components/TarjetaTienda.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'
import './InicioPage.css'

const VALORES = [
  { icono: iconoArbol, clave: 'deforestacion' },
  { icono: iconoSinQuimicos, clave: 'pesticidas' },
  { icono: iconoComunidad, clave: 'comunidades' },
  { icono: iconoEscudo, clave: 'verificado' },
]

/** CCPIP-339: periodos que el visitante puede elegir para los mas vendidos. */
const PERIODOS = ['ULTIMOS_30_DIAS', 'ULTIMOS_3_MESES', 'ESTE_ANIO']

/** CW-01: inicio de la tienda web. */
export default function InicioPage() {
  const { t, i18n } = useTranslation()
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(false)
  const [pestana, setPestana] = useState('destacados')
  const [periodo, setPeriodo] = useState(PERIODOS[0])

  useEffect(() => {
    obtenerInicio().then(setDatos).catch(() => setError(true))
  }, [])

  const productos = datos ? (pestana === 'destacados' ? datos.destacados : datos.novedades) : []

  return (
    <LayoutTienda>
      <main className="inicio-tienda contenedor">
        <section className="hero" aria-labelledby="titulo-hero">
          <div className="hero__texto">
            <p className="overline hero__overline">{t('inicio.hero.overline')}</p>
            <h1 id="titulo-hero" className="hero__titulo">{t('inicio.hero.titulo')}</h1>
            <p className="hero__descripcion">{t('inicio.hero.descripcion')}</p>
            <div className="hero__acciones">
              <Boton tipo="acento" to={RUTAS.buscar}>
                {t('inicio.hero.explorar')}
              </Boton>
              <Boton tipo="secundario" to={RUTAS.comunidades}>
                {t('inicio.hero.conocer')}
              </Boton>
            </div>
          </div>
          <div className="hero__mosaico" aria-hidden="true">
            <ImagenProducto linea="CACAO" className="hero__img hero__img--alta" />
            <ImagenProducto linea="SUPERFRUTOS" className="hero__img" />
            <ImagenProducto linea="COMUNIDAD" className="hero__img" />
          </div>
        </section>

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

        {error && <Alerta tono="error" titulo={t('inicio.error')} descripcion={t('comun.errorConexion')} />}

        {datos?.tiendas.length > 0 && (
          <section className="seccion" aria-labelledby="titulo-comunidades">
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
          <section className="seccion" aria-labelledby="titulo-lineas">
            <div className="seccion__encabezado">
              <h2 id="titulo-lineas" className="seccion__titulo">{t('inicio.lineas')}</h2>
              <Enlace to={RUTAS.buscar} className="enlace-marca">{t('comun.verTodas')}</Enlace>
            </div>
            <ul className="lineas-tienda">
              {datos.lineas.map((l) => (
                <li key={l.codigo}>
                  <Enlace to={`${RUTAS.buscar}?linea=${l.codigo}`} className="linea-tienda">
                    <ImagenProducto linea={l.codigo} className="linea-tienda__img" />
                    <span className="label-m">{l.nombre}</span>
                    <span className="caption">{t('comun.producto', { count: l.total })}</span>
                  </Enlace>
                </li>
              ))}
            </ul>
          </section>
        )}

        {datos && (
          <section className="seccion" aria-labelledby="titulo-descubrir">
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
            {pestana === 'masVendidos' ? (
              <MasVendidos periodo={periodo} onPeriodo={setPeriodo} idioma={i18n.resolvedLanguage} />
            ) : (
              <ul className="grilla-productos" aria-label={t(`inicio.descubrir.${pestana}`)}>
                {productos.map((p) => (
                  <li key={p.id}>
                    <TarjetaProducto producto={p} />
                  </li>
                ))}
              </ul>
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
 * CCPIP-339 (RF-229): los productos mas vendidos del periodo con su productor. Solo el puesto, sin
 * unidades ni montos; si nada se vendio, lo dice en vez de rellenar con otros productos.
 */
function MasVendidos({ periodo, onPeriodo, idioma }) {
  const { t } = useTranslation()
  const [respuesta, setRespuesta] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let vigente = true
    setError(false)
    obtenerMasVendidos(periodo)
      .then((r) => { if (vigente) setRespuesta(r) })
      .catch(() => { if (vigente) setError(true) })
    return () => { vigente = false }
  }, [periodo, idioma])

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
      {!error && !actual && <p className="caption">{t('comun.cargando')}</p>}
      {actual && actual.productos.length === 0 && <p className="body-m">{t('inicio.descubrir.sinVentas')}</p>}
      {actual && actual.productos.length > 0 && (
        <ul className="grilla-productos" aria-label={t('inicio.descubrir.masVendidos')}>
          {actual.productos.map((p, i) => (
            <li key={p.id}>
              <TarjetaProducto producto={p} puesto={i + 1} />
            </li>
          ))}
        </ul>
      )}
      <p className="caption">{t('inicio.descubrir.notaMasVendidos')}</p>
    </div>
  )
}
