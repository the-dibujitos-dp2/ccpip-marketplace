import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCamara from '../../assets/icons/camara.svg'
import iconoCerrar from '../../assets/icons/cerrar.svg'
import iconoCheck from '../../assets/icons/check.svg'
import iconoEstrella from '../../assets/icons/estrella.svg'
import iconoOjo from '../../assets/icons/ojo.svg'
import { eliminarImagen, guardarTienda, obtenerTienda, publicarTienda, subirImagen } from '../../api/productor.js'
import { invalidarTiendaActual } from '../../api/tiendaActual.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Badge from '../../components/Badge.jsx'
import { mensajeError, traducirFaltante } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './TiendaEditorPage.css'

const CATEGORIAS = ['COMUNIDAD', 'CULTIVO', 'PRODUCCION', 'TIENDA_FISICA', 'BRANDING']
const MAX_GALERIA = 24
const MAX_DESTACADOS = 4

function formulario(t) {
  return {
    nombreTienda: t.nombreTienda ?? '',
    descripcionCorta: t.descripcionCorta ?? '',
    historia: t.historia ?? '',
    cita: t.cita ?? '',
    pueblo: t.pueblo ?? '',
    localidad: t.localidad ?? '',
    socios: t.socios ?? '',
    destacados: t.destacados ?? [],
  }
}

/** PW-08: el productor arma y publica la tienda de su comunidad (CCPIP-30). */
export default function TiendaEditorPage({ path }) {
  const { t } = useTranslation()
  const [tienda, setTienda] = useState(null)
  const [f, setF] = useState(null)
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [filtro, setFiltro] = useState('TODO')
  const [categoria, setCategoria] = useState('COMUNIDAD')
  const [eligiendo, setEligiendo] = useState(false)
  const [subiendo, setSubiendo] = useState(null)

  function aplicar(datos) {
    setTienda(datos)
    setF(formulario(datos))
  }
  const categorias = CATEGORIAS.map((c) => ({ valor: c, etiqueta: t(`productor.tienda.categorias.${c}`) }))
  const nombreCategoria = (c) => (CATEGORIAS.includes(c) ? t(`productor.tienda.categorias.${c}`) : null)

  useEffect(() => {
    obtenerTienda().then(aplicar).catch(() => setAlerta({ tono: 'error', titulo: t('productor.tienda.errorCargar') }))
  }, [t])

  const set = (campo) => (e) => setF({ ...f, [campo]: e.target.value })

  async function guardar() {
    const e = {}
    if (!f.nombreTienda.trim()) e.nombreTienda = t('productor.tienda.validacion.nombre')
    if (f.socios !== '' && !(Number(f.socios) > 0)) e.socios = t('productor.tienda.validacion.socios')
    setErrores(e)
    if (Object.keys(e).length) return null
    setOcupado(true)
    setAlerta(null)
    try {
      const guardada = await guardarTienda({ ...f, socios: f.socios === '' ? null : Number(f.socios) })
      aplicar(guardada)
      invalidarTiendaActual()
      setAlerta({
        tono: 'exito',
        titulo: guardada.publicada ? t('productor.tienda.publicados') : t('productor.datos.cambiosGuardados'),
        descripcion: guardada.publicada ? t('productor.tienda.publicadosTexto') : t('productor.tienda.guardadosTexto'),
      })
      return guardada
    } catch (err) {
      // Los mensajes por campo llegan en espanol: en ingles se marca el campo con el mensaje del code
      const campos = err.body?.campos ?? {}
      setErrores(i18n.resolvedLanguage === 'es' ? campos : Object.fromEntries(Object.keys(campos).map((c) => [c, mensajeError(err)])))
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.datos.errorGuardar') })
      return null
    } finally {
      setOcupado(false)
    }
  }

  async function publicar() {
    if (!(await guardar())) return
    setOcupado(true)
    try {
      aplicar(await publicarTienda())
      invalidarTiendaActual()
      setAlerta({ tono: 'exito', titulo: t('productor.tienda.publicada'), descripcion: t('productor.tienda.publicadaTexto') })
    } catch (err) {
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.editor.errorPublicar'), descripcion: err.body?.detalles?.map(traducirFaltante).join(' · ') })
    } finally {
      setOcupado(false)
    }
  }

  async function subir(archivos, tipo) {
    for (const archivo of archivos) {
      setSubiendo(archivo.name)
      try {
        await subirImagen(archivo, { tipo, categoria: tipo === 'GALERIA' ? categoria : undefined })
      } catch (err) {
        setAlerta({ tono: 'error', titulo: t('productor.tienda.noSubio', { nombre: archivo.name }), descripcion: t('productor.tienda.noSubioTexto', { motivo: mensajeError(err, 'productor.datos.seCorto') }) })
      }
    }
    setSubiendo(null)
    await recargar()
  }

  async function quitar(imagen) {
    try {
      await eliminarImagen(imagen.id)
    } catch (err) {
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.datos.errorQuitarFoto') })
    }
    await recargar()
  }

  /** Vuelve a leer la tienda tras subir o quitar fotos; si falla, lo dice sin romper la pantalla. */
  async function recargar() {
    try {
      aplicar(await obtenerTienda())
    } catch {
      setAlerta({ tono: 'error', titulo: t('productor.tienda.errorFotos'), descripcion: t('productor.editor.errorFotosTexto') })
    }
  }

  function alternarDestacado(id) {
    const ya = f.destacados.includes(id)
    if (!ya && f.destacados.length >= MAX_DESTACADOS) return
    setF({ ...f, destacados: ya ? f.destacados.filter((x) => x !== id) : [...f.destacados, id] })
  }

  if (!tienda || !f) {
    return (
      <PaginaProductor path={path} seccion={t('productor.nav.tienda')} titulo={t('productor.tienda.titulo')}>
        {alerta && <Alerta {...alerta} />}
      </PaginaProductor>
    )
  }

  const galeria = tienda.galeria.filter((g) => filtro === 'TODO' || g.categoria === filtro)

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.tienda')} titulo={t('productor.tienda.titulo')}>
      <div className="productor__encabezado">
        <div>
          <h2 className="heading-h2">{t('productor.tienda.asiTeVen')}</h2>
          <p className="body-s">{tienda.publicada ? t('productor.tienda.alGuardar') : t('productor.tienda.noPublicada')}</p>
        </div>
        <div className="productor__acciones-barra">
          {tienda.publicada ? (
            <>
              <Boton tipo="secundario" tamano="M" icono={iconoOjo} to={`/tiendas/${tienda.id}`}>{t('productor.tienda.verPublicada')}</Boton>
              <Boton tamano="M" onClick={guardar} disabled={ocupado}>{t('productor.datos.guardarCambios')}</Boton>
            </>
          ) : (
            <>
              <Boton tipo="secundario" tamano="M" onClick={guardar} disabled={ocupado}>{t('productor.datos.guardarBorrador')}</Boton>
              <Boton tamano="M" onClick={publicar} disabled={ocupado}>{t('productor.tienda.publicar')}</Boton>
            </>
          )}
        </div>
      </div>

      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

      <div className="tienda-editor">
        <div className="tarjeta-panel tienda-editor__formulario">
          <div className="tienda-editor__portada">
            <ImagenProducto url={tienda.portada?.url} linea="PORTADA" alt={t('productor.tienda.portada')} className="tienda-editor__portada-img" />
            <label className="tienda-editor__cambiar-portada">
              <Icono src={iconoCamara} tamano={16} />
              {tienda.portada ? t('productor.tienda.cambiarPortada') : t('productor.tienda.subirPortada')}
              <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" onChange={(e) => { void subir(Array.from(e.target.files ?? []).slice(0, 1), 'PORTADA'); e.target.value = '' }} />
            </label>
          </div>

          <Campo etiqueta={t('productor.tienda.nombre')} value={f.nombreTienda} onChange={set('nombreTienda')} error={errores.nombreTienda} maxLength={120} placeholder="Asoc. Nii Joi" />
          <Campo etiqueta={t('productor.tienda.descripcion')} value={f.descripcionCorta} onChange={set('descripcionCorta')} error={errores.descripcionCorta} maxLength={200} ayuda={t('productor.tienda.descripcionAyuda')} />
          <Campo etiqueta={t('productor.tienda.historia')} tipo="area" rows={5} value={f.historia} onChange={set('historia')} error={errores.historia} maxLength={4000} placeholder={t('productor.tienda.historiaPlaceholder')} />
          <Campo etiqueta={t('productor.tienda.cita')} value={f.cita} onChange={set('cita')} error={errores.cita} maxLength={200} placeholder={t('productor.tienda.citaPlaceholder')} />
          <div className="tienda-editor__fila">
            <Campo etiqueta={t('productor.tienda.pueblo')} value={f.pueblo} onChange={set('pueblo')} error={errores.pueblo} maxLength={80} placeholder="Shipibo-Konibo" />
            <Campo etiqueta={t('productor.tienda.localidad')} value={f.localidad} onChange={set('localidad')} maxLength={80} placeholder="Yarinacocha" />
            <Campo etiqueta={t('productor.tienda.socios')} type="number" min="1" inputMode="numeric" value={f.socios} onChange={set('socios')} error={errores.socios} />
          </div>
          <p className="caption">{t('productor.tienda.registrados', { region: tienda.region, comunidad: tienda.comunidad })}</p>

          <section className="tienda-editor__destacados" aria-labelledby="titulo-destacados">
            <div className="tienda-editor__destacados-cabecera">
              <h3 id="titulo-destacados" className="label-m">{t('productor.tienda.destacados')}</h3>
              {tienda.productosPublicados.length > 0 && (
                <button type="button" className="enlace-marca tienda-editor__elegir" onClick={() => setEligiendo((v) => !v)}>
                  {eligiendo ? t('productor.historia.listo') : t('productor.tienda.elegir')}
                </button>
              )}
            </div>
            {tienda.productosPublicados.length === 0 ? (
              <p className="caption">{t('productor.tienda.sinPublicados', { max: MAX_DESTACADOS })}</p>
            ) : eligiendo ? (
              <ul className="tienda-editor__opciones">
                {tienda.productosPublicados.map((p) => (
                  <li key={p.id}>
                    <label className="casilla">
                      <input type="checkbox" checked={f.destacados.includes(p.id)} disabled={!f.destacados.includes(p.id) && f.destacados.length >= MAX_DESTACADOS} onChange={() => alternarDestacado(p.id)} />
                      <span>{p.nombre}</span>
                    </label>
                  </li>
                ))}
              </ul>
            ) : (
              <ul className="tienda-editor__chips">
                {f.destacados.length === 0 && <li className="caption">{t('productor.tienda.sinElegir')}</li>}
                {f.destacados.map((idProducto) => (
                  <li key={idProducto} className="tienda-editor__chip">
                    <Icono src={iconoEstrella} tamano={14} />
                    {tienda.productosPublicados.find((p) => p.id === idProducto)?.nombre}
                  </li>
                ))}
              </ul>
            )}
            <p className="caption">{t('productor.tienda.hasta', { max: MAX_DESTACADOS })}</p>
          </section>
        </div>

        <section className="tarjeta-panel tienda-editor__galeria" aria-labelledby="titulo-galeria-editor">
          <div className="tienda-editor__destacados-cabecera">
            <h2 id="titulo-galeria-editor" className="heading-h4">{t('productor.tienda.galeria', { total: tienda.galeria.length })}</h2>
            <span className="caption">{t('productor.datos.fotos')}</span>
          </div>
          <div className="tienda-editor__filtros" role="group" aria-label={t('productor.tienda.filtrar')}>
            {[{ valor: 'TODO', etiqueta: t('productor.tienda.todo') }, ...categorias].map((c) => (
              <button key={c.valor} type="button" className={`chip${filtro === c.valor ? ' chip--activo' : ''}`} aria-pressed={filtro === c.valor} onClick={() => setFiltro(c.valor)}>
                {filtro === c.valor && <Icono src={iconoCheck} tamano={14} />}
                {c.etiqueta}
              </button>
            ))}
          </div>
          <ul className="tienda-editor__fotos">
            {galeria.map((g) => (
              <li key={g.id}>
                <img src={g.urlMiniatura} alt={nombreCategoria(g.categoria) ? t('productor.tienda.fotoDe', { categoria: nombreCategoria(g.categoria) }) : t('productor.tienda.fotoGaleria')} />
                <Badge tono="neutro" conPunto={false}>{nombreCategoria(g.categoria)}</Badge>
                <button type="button" className="editor__quitar" aria-label={t('productor.datos.quitarFoto')} onClick={() => void quitar(g)}>
                  <Icono src={iconoCerrar} tamano={14} />
                </button>
              </li>
            ))}
            {tienda.galeria.length < MAX_GALERIA && (
              <li className="tienda-editor__agregar">
                <label>
                  <Icono src={iconoCamara} tamano={20} />
                  {subiendo ? t('productor.datos.subiendo') : t('productor.tienda.agregarFoto')}
                  <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(e) => { void subir(Array.from(e.target.files ?? []), 'GALERIA'); e.target.value = '' }} />
                </label>
              </li>
            )}
          </ul>
          <Campo
            etiqueta={t('productor.tienda.categoriaNuevas')}
            tipo="selector"
            value={categoria}
            onChange={(e) => setCategoria(e.target.value || 'COMUNIDAD')}
            opciones={categorias}
          />
          <p className="caption">{t('productor.tienda.senal')}</p>
        </section>
      </div>
    </PaginaProductor>
  )
}
