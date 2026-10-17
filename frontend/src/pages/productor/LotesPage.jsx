import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarLotes, listarProductos, listarTodosLosLotes } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import { unidades } from '../../formato.js'
import { navigate, RUTAS } from '../../router.js'
import { FormularioLote, TablaLotes, TablaTodosLosLotes } from './Lotes.jsx'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './Lotes.css'

/** PW-06: registrar un lote de cualquiera de sus productos (CCPIP-41); sin producto, todos sus lotes (CCPIP-161). */
export default function LotesPage({ path, search }) {
  const { t } = useTranslation()
  const productoId = new URLSearchParams(search).get('producto')
  const [productos, setProductos] = useState(null)
  const [lotes, setLotes] = useState(null)
  const [cambiando, setCambiando] = useState(!productoId)
  const [noEncontrado, setNoEncontrado] = useState(false)
  const [todos, setTodos] = useState(null)
  const [errorTodos, setErrorTodos] = useState(false)

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

  useEffect(() => {
    // CCPIP-161: sin producto elegido, todos sus lotes por vencimiento proximo
    if (productoId) return
    setErrorTodos(false)
    listarTodosLosLotes()
      .then(setTodos)
      .catch(() => setErrorTodos(true))
  }, [productoId])

  const vigentes = lotes ? lotes.items.filter((l) => l.estado !== 'VENCIDO').length : 0

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.lotes')} titulo={t('productor.panel.registrarLote')}>
      {productos?.length === 0 && (
        <EstadoVacio titulo={t('productor.lotes.primeroProducto')} descripcion={t('productor.lotes.primeroProductoTexto')}>
          <Boton tamano="M" to={RUTAS.productorNuevo}>{t('productor.panel.publicarProducto')}</Boton>
        </EstadoVacio>
      )}

      {noEncontrado && <Alerta tono="error" titulo={t('productor.lotes.noExiste')} descripcion={t('productor.lotes.noExisteTexto')} />}

      {productos?.length > 0 && (cambiando || !productoId) && (
        <div className="tarjeta-panel lotes__selector">
          <Campo
            etiqueta={t('productor.lotes.deQueProducto')}
            tipo="selector"
            placeholder={t('productor.lotes.eligeProducto')}
            value={productoId ?? ''}
            onChange={(e) => e.target.value && navigate(`${RUTAS.productorLotes}?producto=${e.target.value}`)}
            opciones={productos.map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
          />
        </div>
      )}

      {!productoId && productos?.length > 0 && (
        <section className="productor__seccion" aria-labelledby="titulo-todos-lotes">
          <h2 id="titulo-todos-lotes" className="heading-h4">{t('productor.lotes.todos')}</h2>
          <p className="body-s">{t('productor.lotes.todosTexto')}</p>
          {errorTodos && <Alerta tono="error" titulo={t('productor.lotes.errorTodos')} />}
          {todos?.length === 0 && <p className="body-s">{t('productor.lotes.sinLotesTodos')}</p>}
          {todos?.length > 0 && <TablaTodosLosLotes lotes={todos} />}
        </section>
      )}

      {lotes && !cambiando && (
        <>
          <div className="tarjeta-panel lotes__producto">
            <ImagenProducto url={lotes.miniatura} linea={lotes.lineaCodigo} className="tabla__miniatura lotes__miniatura" />
            <div className="lotes__producto-texto">
              <p className="lotes__producto-nombre">{lotes.producto}</p>
              <p className="caption">
                {t('productor.lotes.stockActual', { stock: unidades(lotes.stockTotal), lotes: t('productor.lotes.lote', { count: vigentes }), enVenta: unidades(lotes.enVenta) })}
              </p>
            </div>
            <Boton tipo="terciario" tamano="M" onClick={() => setCambiando(true)}>
              {t('productor.lotes.cambiarProducto')}
            </Boton>
          </div>
          <div className="lotes__cuerpo">
            <FormularioLote productoId={lotes.productoId} requiereVencimiento={lotes.requiereVencimiento} onRegistrado={setLotes} />
            <section className="productor__seccion" aria-labelledby="titulo-lotes">
              <h2 id="titulo-lotes" className="heading-h4">{t('productor.lotes.deEsteProducto')}</h2>
              <TablaLotes lotes={lotes} />
              <Alerta tono="info" titulo={t('productor.lotes.fefo')} descripcion={t('productor.lotes.fefoTexto')} />
            </section>
          </div>
        </>
      )}
    </PaginaProductor>
  )
}
