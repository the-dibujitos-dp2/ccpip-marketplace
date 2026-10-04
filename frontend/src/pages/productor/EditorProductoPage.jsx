import { useCallback, useEffect, useState } from 'react'
import { crearProducto, editarProducto, listarLotes, obtenerProducto, publicarProducto } from '../../api/productor.js'
import { useTiendaActual } from '../../api/tiendaActual.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pasos from '../../components/Pasos.jsx'
import { navigate, RUTAS } from '../../router.js'
import { FormularioLote, TablaLotes } from './Lotes.jsx'
import PaginaProductor from './PaginaProductor.jsx'
import PasoDatos from './PasoDatos.jsx'
import PasoHistoria from './PasoHistoria.jsx'
import './productor.css'
import './Editor.css'

const PASOS = ['Datos', 'Historia', 'Primer lote']
const CLAVES = ['datos', 'historia', 'lote']
const ESTADO = { BORRADOR: ['neutro', 'Borrador'], PUBLICADO: ['exito', 'Publicado'], RETIRADO: ['neutro', 'Despublicado'] }

/**
 * PW-04, PW-05 y PW-06 como un asistente de 3 pasos: datos, historia y primer lote. El mismo
 * asistente sirve para editar (CCPIP-47). El producto nace en borrador al primer guardado.
 */
export default function EditorProductoPage({ path, id, search }) {
  const tienda = useTiendaActual()
  const pasoUrl = CLAVES.indexOf(new URLSearchParams(search).get('paso') ?? 'datos')
  const paso = pasoUrl < 0 ? 0 : pasoUrl
  const [producto, setProducto] = useState(null)
  const [lotes, setLotes] = useState(null)
  const [error, setError] = useState(null)
  const [publicando, setPublicando] = useState(false)
  const [alerta, setAlerta] = useState(null)

  const cargar = useCallback(async () => {
    if (!id) return null
    const p = await obtenerProducto(id)
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
    if (!cuerpo.nombre) throw new Error('Escribe el nombre del producto antes de agregar fotos')
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
      setAlerta({ tono: 'error', titulo: e.message ?? 'No pudimos publicar', descripcion: e.body?.detalles?.join(' · ') })
      await cargar()
    } finally {
      setPublicando(false)
    }
  }

  if (error) {
    return (
      <PaginaProductor path={path} seccion="Productos" titulo="Producto">
        <EstadoVacio titulo="Este producto no existe" descripcion="Puede que no sea tuyo o que la dirección esté mal.">
          <Boton tamano="M" to={RUTAS.productorProductos}>Ir a mis productos</Boton>
        </EstadoVacio>
      </PaginaProductor>
    )
  }

  if (id && !producto) return <PaginaProductor path={path} seccion="Productos" titulo="Producto" />

  const nuevo = !producto || producto.estado === 'BORRADOR'
  const titulo = nuevo ? 'Nuevo producto' : producto.nombre
  const [tono, texto] = ESTADO[producto?.estado ?? 'BORRADOR']

  return (
    <PaginaProductor path={path} seccion={`Productos / ${nuevo ? 'Nuevo' : 'Editar'} / ${PASOS[paso]}`} titulo={titulo}>
      <div className="editor__cabecera">
        <Pasos pasos={PASOS} actual={paso} onIr={id ? (n) => irA(n) : undefined} />
        {producto && <Badge tono={tono}>{texto}</Badge>}
        {producto?.estado === 'PUBLICADO' && (
          <Boton tipo="terciario" tamano="S" to={`/productos/${producto.id}`}>
            Ver en la tienda
          </Boton>
        )}
      </div>

      {paso === 0 && (
        <PasoDatos
          producto={producto}
          tienda={tienda}
          asegurarProducto={asegurarProducto}
          onGuardar={guardarDatos}
          onSiguiente={() => irA(1, producto?.id ?? id)}
          onImagenes={() => cargar()}
        />
      )}

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
              <h2 id="titulo-lotes-producto" className="heading-h4">Lotes de este producto</h2>
              <TablaLotes lotes={lotes} />
            </section>
            <section className="tarjeta-panel" aria-labelledby="titulo-publicar">
              <h2 id="titulo-publicar" className="heading-h4">{producto.estado === 'PUBLICADO' ? 'Producto publicado' : 'Publicar'}</h2>
              {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
              {producto.estado === 'PUBLICADO' ? (
                <p className="body-s">Tu producto está en el catálogo. Los lotes nuevos se suman a lo que está en venta.</p>
              ) : producto.publicable.listo ? (
                <>
                  <p className="body-s">Todo listo: precio, línea y un lote en venta. Al publicar aparece en el catálogo y en tu tienda.</p>
                  <Boton onClick={publicar} disabled={publicando}>
                    {publicando ? 'Publicando…' : producto.estado === 'RETIRADO' ? 'Volver a publicar' : 'Publicar producto'}
                  </Boton>
                </>
              ) : (
                <>
                  <p className="body-s">Para publicar todavía falta:</p>
                  <ul className="editor__faltan">
                    {producto.publicable.faltan.map((f) => (
                      <li key={f}>{f}</li>
                    ))}
                  </ul>
                  {producto.publicable.faltan.some((f) => f.includes('tienda')) && (
                    <Boton tipo="secundario" tamano="M" to={RUTAS.productorTienda}>Ir a Mi tienda</Boton>
                  )}
                </>
              )}
              <Boton tipo="terciario" tamano="M" to={RUTAS.productorProductos}>
                Volver a mis productos
              </Boton>
            </section>
          </div>
        </div>
      )}
    </PaginaProductor>
  )
}
