import { useEffect, useState } from 'react'
import { listarLotes, listarProductos } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import { navigate, RUTAS } from '../../router.js'
import { FormularioLote, TablaLotes } from './Lotes.jsx'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './Lotes.css'

const unidades = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 3 })

/** PW-06: registrar un lote de cualquiera de sus productos (CCPIP-41). */
export default function LotesPage({ path, search }) {
  const productoId = new URLSearchParams(search).get('producto')
  const [productos, setProductos] = useState(null)
  const [lotes, setLotes] = useState(null)
  const [cambiando, setCambiando] = useState(!productoId)
  const [noEncontrado, setNoEncontrado] = useState(false)

  useEffect(() => {
    // Todos sus productos, sin importar el estado: tambien se reponen los despublicados
    Promise.all(['PUBLICADO', 'BORRADOR', 'RETIRADO'].map((estado) => listarProductos({ estado })))
      .then((listas) => setProductos(listas.flatMap((l) => l.items)))
      .catch(() => setProductos([]))
  }, [])

  useEffect(() => {
    setLotes(null)
    setNoEncontrado(false)
    if (productoId) {
      setCambiando(false)
      listarLotes(productoId)
        .then(setLotes)
        .catch(() => {
          // Un producto ajeno o inexistente: el servidor responde 404 (RNF-05)
          setNoEncontrado(true)
          setCambiando(true)
        })
    }
  }, [productoId])

  const vigentes = lotes ? lotes.items.filter((l) => l.estado !== 'VENCIDO').length : 0

  return (
    <PaginaProductor path={path} seccion="Lotes" titulo="Registrar lote">
      {productos?.length === 0 && (
        <EstadoVacio titulo="Primero crea un producto" descripcion="Los lotes se registran para un producto.">
          <Boton tamano="M" to={RUTAS.productorNuevo}>Publicar producto</Boton>
        </EstadoVacio>
      )}

      {noEncontrado && <Alerta tono="error" titulo="Ese producto no existe" descripcion="Puede que no sea tuyo o que el enlace esté mal. Elige uno de tus productos." />}

      {productos?.length > 0 && (cambiando || !productoId) && (
        <div className="tarjeta-panel lotes__selector">
          <Campo
            etiqueta="¿De qué producto es el lote?"
            tipo="selector"
            placeholder="Elige un producto"
            value={productoId ?? ''}
            onChange={(e) => e.target.value && navigate(`${RUTAS.productorLotes}?producto=${e.target.value}`)}
            opciones={productos.map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
          />
        </div>
      )}

      {lotes && !cambiando && (
        <>
          <div className="tarjeta-panel lotes__producto">
            <ImagenProducto url={lotes.miniatura} linea={lotes.lineaCodigo} className="tabla__miniatura lotes__miniatura" />
            <div className="lotes__producto-texto">
              <p className="lotes__producto-nombre">{lotes.producto}</p>
              <p className="caption">
                Stock actual {unidades.format(lotes.stockTotal)} und en {vigentes} {vigentes === 1 ? 'lote' : 'lotes'} · {unidades.format(lotes.enVenta)} en venta
              </p>
            </div>
            <Boton tipo="terciario" tamano="M" onClick={() => setCambiando(true)}>
              Cambiar producto
            </Boton>
          </div>
          <div className="lotes__cuerpo">
            <FormularioLote productoId={lotes.productoId} requiereVencimiento={lotes.requiereVencimiento} onRegistrado={setLotes} />
            <section className="productor__seccion" aria-labelledby="titulo-lotes">
              <h2 id="titulo-lotes" className="heading-h4">Lotes de este producto</h2>
              <TablaLotes lotes={lotes} />
              <Alerta tono="info" titulo="Se vende primero el lote que vence antes" descripcion="Un lote vencido no se vende y deja de contar como stock disponible." />
            </section>
          </div>
        </>
      )}
    </PaginaProductor>
  )
}
