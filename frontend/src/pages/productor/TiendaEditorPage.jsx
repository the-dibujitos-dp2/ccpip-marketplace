import { useEffect, useState } from 'react'
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
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './TiendaEditorPage.css'

const CATEGORIAS = [
  { valor: 'COMUNIDAD', etiqueta: 'Comunidad' },
  { valor: 'CULTIVO', etiqueta: 'Cultivo' },
  { valor: 'PRODUCCION', etiqueta: 'Producción' },
  { valor: 'TIENDA_FISICA', etiqueta: 'Tienda física' },
  { valor: 'BRANDING', etiqueta: 'Branding' },
]
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
  const [tienda, setTienda] = useState(null)
  const [f, setF] = useState(null)
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [filtro, setFiltro] = useState('TODO')
  const [categoria, setCategoria] = useState('COMUNIDAD')
  const [eligiendo, setEligiendo] = useState(false)
  const [subiendo, setSubiendo] = useState(null)

  function aplicar(t) {
    setTienda(t)
    setF(formulario(t))
  }

  useEffect(() => {
    obtenerTienda().then(aplicar).catch(() => setAlerta({ tono: 'error', titulo: 'No pudimos cargar tu tienda' }))
  }, [])

  const set = (campo) => (e) => setF({ ...f, [campo]: e.target.value })

  async function guardar() {
    const e = {}
    if (!f.nombreTienda.trim()) e.nombreTienda = 'Escribe el nombre de tu tienda'
    if (f.socios !== '' && !(Number(f.socios) > 0)) e.socios = 'Debe ser un número positivo'
    setErrores(e)
    if (Object.keys(e).length) return null
    setOcupado(true)
    setAlerta(null)
    try {
      const t = await guardarTienda({ ...f, socios: f.socios === '' ? null : Number(f.socios) })
      aplicar(t)
      invalidarTiendaActual()
      setAlerta({ tono: 'exito', titulo: t.publicada ? 'Cambios publicados' : 'Cambios guardados', descripcion: t.publicada ? 'Ya se ven en tu tienda.' : 'Publica tu tienda cuando esté lista.' })
      return t
    } catch (err) {
      setErrores(err.body?.campos ?? {})
      setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos guardar' })
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
      setAlerta({ tono: 'exito', titulo: '¡Tu tienda está publicada!', descripcion: 'Los compradores ya pueden conocer a tu comunidad.' })
    } catch (err) {
      setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos publicar', descripcion: err.body?.detalles?.join(' · ') })
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
        setAlerta({ tono: 'error', titulo: `No se subió “${archivo.name}”`, descripcion: `${err.message ?? 'Se cortó la conexión'}. Las demás fotos se conservan; vuelve a intentarlo.` })
      }
    }
    setSubiendo(null)
    await recargar()
  }

  async function quitar(imagen) {
    try {
      await eliminarImagen(imagen.id)
    } catch (err) {
      setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos quitar la foto' })
    }
    await recargar()
  }

  /** Vuelve a leer la tienda tras subir o quitar fotos; si falla, lo dice sin romper la pantalla. */
  async function recargar() {
    try {
      aplicar(await obtenerTienda())
    } catch {
      setAlerta({ tono: 'error', titulo: 'No pudimos actualizar tus fotos', descripcion: 'Recarga la página para verlas.' })
    }
  }

  function alternarDestacado(id) {
    const ya = f.destacados.includes(id)
    if (!ya && f.destacados.length >= MAX_DESTACADOS) return
    setF({ ...f, destacados: ya ? f.destacados.filter((x) => x !== id) : [...f.destacados, id] })
  }

  if (!tienda || !f) {
    return (
      <PaginaProductor path={path} seccion="Mi tienda" titulo="Editar mi tienda">
        {alerta && <Alerta {...alerta} />}
      </PaginaProductor>
    )
  }

  const galeria = tienda.galeria.filter((g) => filtro === 'TODO' || g.categoria === filtro)

  return (
    <PaginaProductor path={path} seccion="Mi tienda" titulo="Editar mi tienda">
      <div className="productor__encabezado">
        <div>
          <h2 className="heading-h2">Así te ven los compradores</h2>
          <p className="body-s">{tienda.publicada ? 'Los cambios se publican al guardar.' : 'Tu tienda aún no está publicada.'}</p>
        </div>
        <div className="productor__acciones-barra">
          {tienda.publicada ? (
            <>
              <Boton tipo="secundario" tamano="M" icono={iconoOjo} to={`/tiendas/${tienda.id}`}>Ver tienda publicada</Boton>
              <Boton tamano="M" onClick={guardar} disabled={ocupado}>Guardar cambios</Boton>
            </>
          ) : (
            <>
              <Boton tipo="secundario" tamano="M" onClick={guardar} disabled={ocupado}>Guardar borrador</Boton>
              <Boton tamano="M" onClick={publicar} disabled={ocupado}>Publicar tienda</Boton>
            </>
          )}
        </div>
      </div>

      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

      <div className="tienda-editor">
        <div className="tarjeta-panel tienda-editor__formulario">
          <div className="tienda-editor__portada">
            <ImagenProducto url={tienda.portada?.url} linea="PORTADA" alt="Portada de la tienda" className="tienda-editor__portada-img" />
            <label className="tienda-editor__cambiar-portada">
              <Icono src={iconoCamara} tamano={16} />
              {tienda.portada ? 'Cambiar portada' : 'Subir portada'}
              <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" onChange={(e) => { void subir(Array.from(e.target.files ?? []).slice(0, 1), 'PORTADA'); e.target.value = '' }} />
            </label>
          </div>

          <Campo etiqueta="Nombre de la tienda" value={f.nombreTienda} onChange={set('nombreTienda')} error={errores.nombreTienda} maxLength={120} placeholder="Asoc. Nii Joi" />
          <Campo etiqueta="Descripción de mi tienda" value={f.descripcionCorta} onChange={set('descripcionCorta')} error={errores.descripcionCorta} maxLength={200} ayuda="Una o dos líneas: aparece bajo el nombre de la tienda." />
          <Campo etiqueta="Historia de mi comunidad" tipo="area" rows={5} value={f.historia} onChange={set('historia')} error={errores.historia} maxLength={4000} placeholder="Quiénes son, dónde viven y cómo producen." />
          <Campo etiqueta="Cita de la comunidad" value={f.cita} onChange={set('cita')} error={errores.cita} maxLength={200} placeholder="“Las plantas del monte curan y alimentan.”" />
          <div className="tienda-editor__fila">
            <Campo etiqueta="Pueblo de pertenencia" value={f.pueblo} onChange={set('pueblo')} error={errores.pueblo} maxLength={80} placeholder="Shipibo-Konibo" />
            <Campo etiqueta="Localidad" value={f.localidad} onChange={set('localidad')} maxLength={80} placeholder="Yarinacocha" />
            <Campo etiqueta="Socias y socios" type="number" min="1" inputMode="numeric" value={f.socios} onChange={set('socios')} error={errores.socios} />
          </div>
          <p className="caption">Región: {tienda.region} · Comunidad: {tienda.comunidad}. Los registra la Cámara.</p>

          <section className="tienda-editor__destacados" aria-labelledby="titulo-destacados">
            <div className="tienda-editor__destacados-cabecera">
              <h3 id="titulo-destacados" className="label-m">Productos destacados en mi tienda</h3>
              {tienda.productosPublicados.length > 0 && (
                <button type="button" className="enlace-marca tienda-editor__elegir" onClick={() => setEligiendo((v) => !v)}>
                  {eligiendo ? 'Listo' : 'Elegir productos'}
                </button>
              )}
            </div>
            {tienda.productosPublicados.length === 0 ? (
              <p className="caption">Cuando publiques productos podrás destacar hasta 4.</p>
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
                {f.destacados.length === 0 && <li className="caption">Aún no eliges productos.</li>}
                {f.destacados.map((idProducto) => (
                  <li key={idProducto} className="tienda-editor__chip">
                    <Icono src={iconoEstrella} tamano={14} />
                    {tienda.productosPublicados.find((p) => p.id === idProducto)?.nombre}
                  </li>
                ))}
              </ul>
            )}
            <p className="caption">Hasta {MAX_DESTACADOS}. Se muestran primero en tu tienda.</p>
          </section>
        </div>

        <section className="tarjeta-panel tienda-editor__galeria" aria-labelledby="titulo-galeria-editor">
          <div className="tienda-editor__destacados-cabecera">
            <h2 id="titulo-galeria-editor" className="heading-h4">Galería ({tienda.galeria.length})</h2>
            <span className="caption">Fotos</span>
          </div>
          <div className="tienda-editor__filtros" role="group" aria-label="Filtrar galería">
            {[{ valor: 'TODO', etiqueta: 'Todo' }, ...CATEGORIAS].map((c) => (
              <button key={c.valor} type="button" className={`chip${filtro === c.valor ? ' chip--activo' : ''}`} aria-pressed={filtro === c.valor} onClick={() => setFiltro(c.valor)}>
                {filtro === c.valor && <Icono src={iconoCheck} tamano={14} />}
                {c.etiqueta}
              </button>
            ))}
          </div>
          <ul className="tienda-editor__fotos">
            {galeria.map((g) => (
              <li key={g.id}>
                <img src={g.urlMiniatura} alt={`Foto de ${CATEGORIAS.find((c) => c.valor === g.categoria)?.etiqueta ?? 'la galería'}`} />
                <Badge tono="neutro" conPunto={false}>{CATEGORIAS.find((c) => c.valor === g.categoria)?.etiqueta}</Badge>
                <button type="button" className="editor__quitar" aria-label="Quitar foto" onClick={() => void quitar(g)}>
                  <Icono src={iconoCerrar} tamano={14} />
                </button>
              </li>
            ))}
            {tienda.galeria.length < MAX_GALERIA && (
              <li className="tienda-editor__agregar">
                <label>
                  <Icono src={iconoCamara} tamano={20} />
                  {subiendo ? 'Subiendo…' : 'Agregar foto'}
                  <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(e) => { void subir(Array.from(e.target.files ?? []), 'GALERIA'); e.target.value = '' }} />
                </label>
              </li>
            )}
          </ul>
          <Campo
            etiqueta="Categoría de las fotos nuevas"
            tipo="selector"
            value={categoria}
            onChange={(e) => setCategoria(e.target.value || 'COMUNIDAD')}
            opciones={CATEGORIAS}
          />
          <p className="caption">Si la señal se corta, solo se reintenta la foto que faltó: las demás se conservan.</p>
        </section>
      </div>
    </PaginaProductor>
  )
}
