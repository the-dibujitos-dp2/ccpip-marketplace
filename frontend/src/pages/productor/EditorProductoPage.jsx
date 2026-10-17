import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { crearProducto, editarProducto, listarLotes, obtenerProducto, publicarProducto } from '../../api/productor.js'
import { useTiendaActual } from '../../api/tiendaActual.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pasos from '../../components/Pasos.jsx'
import { esFaltanteDeTienda, mensajeError, traducirFaltante } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import { navigate, RUTAS } from '../../router.js'
import { FormularioLote, TablaLotes } from './Lotes.jsx'
import PaginaProductor from './PaginaProductor.jsx'
import PanelTraduccion from './PanelTraduccion.jsx'
import PasoDatos from './PasoDatos.jsx'
import PasoHistoria from './PasoHistoria.jsx'
import './productor.css'
import './Editor.css'

const CLAVES = ['datos', 'historia', 'lote']
const ESTADO = { BORRADOR: ['neutro', 'borrador'], PUBLICADO: ['exito', 'publicado'], RETIRADO: ['neutro', 'despublicado'] }

/**
 * PW-04, PW-05 y PW-06 como un asistente de 3 pasos: datos, historia y primer lote. El mismo
 * asistente sirve para editar (CCPIP-47). El producto nace en borrador al primer guardado.
 */
export default function EditorProductoPage({ path, id, search }) {
  const { t } = useTranslation()
  const tienda = useTiendaActual()
  const pasoUrl = CLAVES.indexOf(new URLSearchParams(search).get('paso') ?? 'datos')
  const paso = pasoUrl < 0 ? 0 : pasoUrl
  const [producto, setProducto] = useState(null)
  const [lotes, setLotes] = useState(null)
  const [error, setError] = useState(null)
  const [publicando, setPublicando] = useState(false)
  const [alerta, setAlerta] = useState(null)

  // `cual` permite recargar un borrador recien creado aunque este render aun tenga id null
  const cargar = useCallback(async (cual = id) => {
    if (!cual) return null
    const p = await obtenerProducto(cual)
    setProducto(p)
    return p
  }, [id])

  useEffect(() => {
    setError(null)
    if (!id) {
      setProducto(null)
      return
    }
    cargar().catch((e) => setError(e))
  }, [id, cargar])

  useEffect(() => {
    if (id && paso === 2) listarLotes(id).then(setLotes).catch(() => setLotes(null))
  }, [id, paso])

  function irA(n, nuevoId = id) {
    navigate(`${RUTAS.productorProductos}/${nuevoId}?paso=${CLAVES[n]}`, { replace: !id })
  }

  /** Crea el borrador si aun no existe (para subir fotos antes de guardar) y devuelve su id. */
  async function asegurarProducto(cuerpo) {
    if (id) return id
    if (!cuerpo.nombre) throw new Error(i18n.t('productor.editor.nombreAntesFotos'))
    const creado = await crearProducto(cuerpo)
    setProducto(creado)
    navigate(`${RUTAS.productorProductos}/${creado.id}?paso=datos`, { replace: true })
    return creado.id
  }

  async function guardarDatos(cuerpo) {
    if (id) {
      setProducto(await editarProducto(id, cuerpo))
      return id
    }
    const creado = await crearProducto(cuerpo)
    setProducto(creado)
    navigate(`${RUTAS.productorProductos}/${creado.id}?paso=datos`, { replace: true })
    return creado.id
  }

  async function publicar() {
    setPublicando(true)
    setAlerta(null)
    try {
      await publicarProducto(id)
      navigate(`${RUTAS.productorProductos}?publicado=1`)
    } catch (e) {
      setAlerta({ tono: 'error', titulo: mensajeError(e, 'productor.editor.errorPublicar'), descripcion: e.body?.detalles?.map(traducirFaltante).join(' · ') })
      await cargar()
    } finally {
      setPublicando(false)
    }
  }

  if (error) {
    return (
      <PaginaProductor path={path} seccion={t('productor.nav.productos')} titulo={t('productor.editor.producto')}>
        <EstadoVacio titulo={t('productor.editor.noExiste')} descripcion={t('productor.editor.noExisteTexto')}>
          <Boton tamano="M" to={RUTAS.productorProductos}>{t('productor.editor.irProductos')}</Boton>
        </EstadoVacio>
      </PaginaProductor>
    )
  }

  if (id && !producto) return <PaginaProductor path={path} seccion={t('productor.nav.productos')} titulo={t('productor.editor.producto')} />

  const nuevo = !producto || producto.estado === 'BORRADOR'
  const titulo = nuevo ? t('productor.editor.nuevoProducto') : producto.nombre
  const [tono, claveEstado] = ESTADO[producto?.estado ?? 'BORRADOR']
  const pasos = CLAVES.map((c) => t(`productor.editor.pasos.${c}`))

  return (
    <PaginaProductor path={path} seccion={`${t('productor.nav.productos')} / ${nuevo ? t('productor.editor.nuevo') : t('productor.editor.editar')} / ${pasos[paso]}`} titulo={titulo}>
      <div className="editor__cabecera">
        <Pasos pasos={pasos} actual={paso} onIr={id ? (n) => irA(n) : undefined} />
        {producto && <Badge tono={tono}>{t(`productor.productos.badge.${claveEstado}`)}</Badge>}
        {producto?.estado === 'PUBLICADO' && (
          <Boton tipo="terciario" tamano="S" to={`/productos/${producto.id}`}>
            {t('productor.productos.verEnTienda')}
          </Boton>
        )}
      </div>

      {alerta && paso !== 2 && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

      {paso === 0 && (
        <PasoDatos
          producto={producto}
          tienda={tienda}
          asegurarProducto={asegurarProducto}
          onGuardar={guardarDatos}
          onSiguiente={() => irA(1, producto?.id ?? id)}
          onImagenes={(cual) => cargar(cual).catch(() => setAlerta({ tono: 'error', titulo: t('productor.editor.errorFotos'), descripcion: t('productor.editor.errorFotosTexto') }))}
        />
      )}
      {/* CCPIP-147: traducir el producto y su historia aprobada; se recarga al guardar por si cambio el original (CCPIP-373) */}
      {paso === 0 && producto?.id && <PanelTraduccion key={`${producto.id}-${producto.nombre}-${producto.descripcion}`} productoId={producto.id} />}

      {paso === 1 && producto && (
        <PasoHistoria
          producto={producto}
          onAtras={() => irA(0)}
          onOmitir={() => irA(2)}
          onAprobada={async () => {
            await cargar()
            irA(2)
          }}
        />
      )}

      {paso === 2 && producto && (
        <div className="editor__lote">
          <div className="editor__lote-columna">
            <FormularioLote
              productoId={producto.id}
              requiereVencimiento={lotes?.requiereVencimiento ?? true}
              sugerencia={lotes && lotes.items.length === 0 ? 'L-0001' : undefined}
              onRegistrado={async (l) => {
                setLotes(l)
                await cargar()
              }}
            />
          </div>
          <div className="editor__lote-columna">
            <section className="productor__seccion" aria-labelledby="titulo-lotes-producto">
              <h2 id="titulo-lotes-producto" className="heading-h4">{t('productor.lotes.deEsteProducto')}</h2>
              <TablaLotes lotes={lotes} />
            </section>
            <section className="tarjeta-panel" aria-labelledby="titulo-publicar">
              <h2 id="titulo-publicar" className="heading-h4">{producto.estado === 'PUBLICADO' ? t('productor.productos.publicado') : t('productor.productos.publicar')}</h2>
              {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
              {producto.estado === 'PUBLICADO' ? (
                <p className="body-s">{t('productor.editor.enCatalogo')}</p>
              ) : producto.publicable.listo ? (
                <>
                  <p className="body-s">{t('productor.editor.todoListo')}</p>
                  <Boton onClick={publicar} disabled={publicando}>
                    {publicando ? t('productor.editor.publicando') : producto.estado === 'RETIRADO' ? t('productor.productos.volverPublicar') : t('productor.panel.publicarProducto')}
                  </Boton>
                </>
              ) : (
                <>
                  <p className="body-s">{t('productor.editor.falta')}</p>
                  <ul className="editor__faltan">
                    {producto.publicable.faltan.map((f) => (
                      <li key={f}>{traducirFaltante(f)}</li>
                    ))}
                  </ul>
                  {producto.publicable.faltan.some(esFaltanteDeTienda) && (
                    <Boton tipo="secundario" tamano="M" to={RUTAS.productorTienda}>{t('productor.editor.irTienda')}</Boton>
                  )}
                </>
              )}
              <Boton tipo="terciario" tamano="M" to={RUTAS.productorProductos}>
                {t('productor.editor.volverProductos')}
              </Boton>
            </section>
          </div>
        </div>
      )}
    </PaginaProductor>
  )
}
