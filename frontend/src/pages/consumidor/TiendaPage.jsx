import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEstrella from '../../assets/icons/estrella.svg'
import { obtenerTienda } from '../../api/catalogo.js'
import AvisoTraduccion from '../../components/AvisoTraduccion.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import SelloOrigen from '../../components/SelloOrigen.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'
import { idiomaSegun, textoSegun } from '../../traduccion.js'
import './TiendaPage.css'

// CCPIP-171: campos traducibles de la tienda, con los nombres que usa el catalogo
const CAMPOS_TIENDA = ['nombre', 'descripcion', 'historia', 'cita']

/**
 * CW-04: tienda de la comunidad (CCPIP-154, CCPIP-56). Publica, sin sesion. En ingles, la historia
 * traducida lleva su etiqueta y "Ver original" (CCPIP-171).
 */
export default function TiendaPage({ id }) {
  const { t } = useTranslation()
  const [tienda, setTienda] = useState(null)
  const [error, setError] = useState(null)
  const [linea, setLinea] = useState('TODOS')
  const [copiado, setCopiado] = useState(false)
  const [verOriginal, setVerOriginal] = useState(false)

  useEffect(() => {
    let vigente = true
    setTienda(null)
    setError(null)
    setLinea('TODOS')
    setVerOriginal(false)
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
            titulo={error.status === 404 ? t('tienda.noDisponible') : t('tienda.error')}
            descripcion={error.status === 404 ? t('tienda.preparando') : t('comun.errorConexion')}
          >
            <Boton tamano="M" to={RUTAS.comunidades}>
              {t('tienda.verComunidades')}
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
  const { traducciones } = tienda
  const texto = (campo) => textoSegun(traducciones, campo, tienda[campo], verOriginal)
  const idioma = (campo) => idiomaSegun(traducciones, campo, verOriginal)

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
          <ImagenProducto url={tienda.portada} linea="PORTADA" alt={t('tienda.portada', { nombre: tienda.nombre })} className="tienda-publica__portada-img" />
        </div>
        <div className="contenedor tienda-publica__contenido">
          <header className="tienda-publica__cabecera">
            <span className="tienda-publica__avatar">
              <ImagenProducto linea="COMUNIDAD" className="tienda-publica__avatar-img" />
            </span>
            <div className="tienda-publica__identidad">
              {tienda.origenVerificado && <SelloOrigen variante="compacto" />}
              <h1 className="display-l" lang={idioma('nombre')}>{texto('nombre')}</h1>
              <p className="body-s">
                {[tienda.pueblo && t('tienda.pueblo', { pueblo: tienda.pueblo }), [tienda.localidad, tienda.region].filter(Boolean).join(', '), tienda.socios && t('tienda.socios', { count: tienda.socios })]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
            </div>
            <dl className="tienda-publica__cifras">
              <div>
                <dt>{t('tienda.cifraProductos')}</dt>
                <dd>{tienda.productos.length}</dd>
              </div>
              {tienda.desde && (
                <div>
                  <dt>{t('tienda.cifraDesde')}</dt>
                  <dd>{tienda.desde}</dd>
                </div>
              )}
            </dl>
            <Boton tipo="secundario" tamano="M" onClick={compartir}>
              {copiado ? t('tienda.copiado') : t('tienda.compartir')}
            </Boton>
          </header>

          <div className={`tienda-publica__historia${tienda.galeria?.length > 0 ? ' tienda-publica__historia--galeria' : ''}`}>
            <section aria-labelledby="titulo-nuestra-historia">
              <h2 id="titulo-nuestra-historia" className="seccion__titulo">{t('tienda.historia')}</h2>
              <AvisoTraduccion traducciones={traducciones} campos={CAMPOS_TIENDA} verOriginal={verOriginal} onAlternar={() => setVerOriginal((v) => !v)} />
              {tienda.cita && <p className="tienda-publica__cita" lang={idioma('cita')}>“{texto('cita')}”</p>}
              {tienda.historia && <p className="body-m tienda-publica__relato" lang={idioma('historia')}>{texto('historia')}</p>}
              {tienda.descripcion && !tienda.historia && <p className="body-m" lang={idioma('descripcion')}>{texto('descripcion')}</p>}
            </section>
            {tienda.galeria?.length > 0 && (
              <section aria-labelledby="titulo-galeria">
                <h2 id="titulo-galeria" className="heading-h4">{t('tienda.galeria')}</h2>
                <ul className="tienda-publica__galeria">
                  {tienda.galeria.slice(0, 6).map((g) => (
                    <li key={g.id}>
                      <a href={g.url} target="_blank" rel="noreferrer">
                        <img src={g.miniatura} alt={t('tienda.foto', { nombre: tienda.nombre })} loading="lazy" />
                      </a>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </div>

          {tienda.lineas?.length > 0 && (
            <Pestanas
              etiqueta={t('tienda.porLinea')}
              valor={linea}
              onCambiar={setLinea}
              opciones={[
                { valor: 'TODOS', etiqueta: t('tienda.todos', { total: tienda.productos.length }) },
                ...tienda.lineas.map((l) => ({ valor: l.codigo, etiqueta: `${l.nombre} (${l.total})` })),
              ]}
            />
          )}

          {linea === 'TODOS' && destacados.length > 0 && (
            <p className="caption tienda-publica__destacados">
              <Icono src={iconoEstrella} tamano={16} />
              {t('tienda.destacados', { nombre: tienda.nombre })}
            </p>
          )}

          {tienda.productos.length === 0 ? (
            <EstadoVacio titulo={t('tienda.sinProductos')} descripcion={t('tienda.sinProductosTexto')} />
          ) : (
            <ul className="grilla-productos" aria-label={t('tienda.productosDe', { nombre: tienda.nombre })}>
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
