import { useEffect, useRef, useState } from 'react'
import iconoBuscar from '../../assets/icons/buscar.svg'
import iconoEditar from '../../assets/icons/editar.svg'
import iconoInfo from '../../assets/icons/info-pequeno.svg'
import iconoMas from '../../assets/icons/mas.svg'
import iconoMasVertical from '../../assets/icons/mas-vertical.svg'
import { listarProductos, publicarProducto, retirarProducto } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import { precio } from '../../formato.js'
import { RUTAS } from '../../router.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './ProductosPage.css'

const unidades = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 3 })
const MODALIDAD = { MINORISTA: 'Minorista', MAYORISTA: 'Mayorista', AMBAS: 'Minorista y mayorista' }
const ESTADOS = [
  { valor: 'PUBLICADO', etiqueta: 'Publicados' },
  { valor: 'BORRADOR', etiqueta: 'Borradores' },
  { valor: 'RETIRADO', etiqueta: 'Despublicados' },
]

function badge(fila) {
  if (fila.estado === 'BORRADOR') return <Badge tono="neutro">Borrador</Badge>
  if (fila.estado === 'RETIRADO') return <Badge tono="neutro">Despublicado</Badge>
  if (fila.alerta === 'LOTE_POR_VENCER') return <Badge tono="aviso">Lote por vencer</Badge>
  if (fila.alerta === 'SIN_STOCK') return <Badge tono="error">Sin stock en venta</Badge>
  if (fila.alerta === 'STOCK_BAJO') return <Badge tono="error">Stock bajo</Badge>
  return <Badge tono="exito">Publicado</Badge>
}

function ubicaciones(stock) {
  if (!stock.length) return '—'
  return stock.map((s) => `${s.tipo === 'ALMACEN_PRODUCTOR' ? 'Almacén' : s.ubicacion.replace(/^Centro (Nacional|Regional) /, '')} ${unidades.format(s.cantidad)}`).join(' · ')
}

/** PW-03: los productos del productor por estado (CCPIP-160). Solo ve los suyos (RNF-05). */
export default function ProductosPage({ path, search }) {
  const inicial = new URLSearchParams(search)
  const [estado, setEstado] = useState(inicial.get('estado') ?? 'PUBLICADO')
  const [q, setQ] = useState(inicial.get('q') ?? '')
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(false)
  const [menu, setMenu] = useState(null)
  const [confirmar, setConfirmar] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [aviso, setAviso] = useState(inicial.get('publicado') ? { tono: 'exito', titulo: 'Producto publicado', descripcion: 'Ya aparece en el catálogo y en tu tienda.' } : null)
  const menuRef = useRef(null)

  function cargar(e = estado, termino = q) {
    setError(false)
    return listarProductos({ estado: e, q: termino }).then(setDatos).catch(() => setError(true))
  }

  // La busqueda de la barra superior llega por la URL, aun estando ya en esta pagina
  const qUrl = inicial.get('q')
  useEffect(() => {
    if (qUrl !== null) setQ(qUrl)
  }, [qUrl])

  useEffect(() => {
    const t = setTimeout(() => cargar(estado, q), q ? 250 : 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [estado, q])

  useEffect(() => {
    if (menu === null) return undefined
    const cerrar = (e) => !menuRef.current?.contains(e.target) && setMenu(null)
    document.addEventListener('mousedown', cerrar)
    return () => document.removeEventListener('mousedown', cerrar)
  }, [menu])

  async function cambiarEstado(fila, accion) {
    setOcupado(true)
    try {
      if (accion === 'retirar') await retirarProducto(fila.id)
      else await publicarProducto(fila.id)
      setAviso(
        accion === 'retirar'
          ? { tono: 'exito', titulo: 'Producto despublicado', descripcion: 'Ya no aparece en el catálogo. Puedes volver a publicarlo cuando quieras.' }
          : { tono: 'exito', titulo: 'Producto publicado', descripcion: 'Ya aparece en el catálogo y en tu tienda.' },
      )
      await cargar()
    } catch (e) {
      setAviso({ tono: 'error', titulo: e.message ?? 'No se pudo cambiar el estado', descripcion: e.body?.detalles?.join(' · ') })
    } finally {
      setOcupado(false)
      setConfirmar(null)
    }
  }

  const conteo = datos?.conteo ?? {}

  return (
    <PaginaProductor path={path} seccion="Productos" titulo="Mis productos">
      <div className="productos__barra">
        <label className="productos__buscador">
          <Icono src={iconoBuscar} tamano={20} />
          <span className="visually-hidden">Buscar producto</span>
          <input type="search" placeholder="Buscar producto…" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
          Publicar producto
        </Boton>
      </div>

      <Pestanas
        etiqueta="Estado de los productos"
        valor={estado}
        onCambiar={(v) => {
          setEstado(v)
          setMenu(null)
        }}
        opciones={ESTADOS.map((e) => ({ valor: e.valor, etiqueta: `${e.etiqueta} (${conteo[e.valor] ?? 0})` }))}
      />

      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {error && <Alerta tono="error" titulo="No pudimos cargar tus productos" descripcion="Revisa tu conexión e inténtalo otra vez." />}

      {datos?.items.length === 0 && (
        <EstadoVacio
          titulo={q ? `No encontramos “${q}”` : estado === 'PUBLICADO' ? 'Aún no tienes productos publicados' : estado === 'BORRADOR' ? 'No tienes borradores' : 'No tienes productos despublicados'}
          descripcion={estado === 'PUBLICADO' && !q ? 'Publica tu primer producto y cuéntale al comprador la historia de tu comunidad.' : undefined}
        >
          {estado === 'PUBLICADO' && !q && (
            <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
              Publicar producto
            </Boton>
          )}
        </EstadoVacio>
      )}

      {datos?.items.length > 0 && (
        <div className="tabla" ref={menuRef}>
          <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
            <span className="tabla__celda tabla__celda--flex">Producto</span>
            <span className="tabla__celda productos__col-precio">Precio</span>
            <span className="tabla__celda productos__col-venta">En venta (acopios)</span>
            <span className="tabla__celda productos__col-modalidad">Modalidad</span>
            <span className="tabla__celda productos__col-ubicacion">Ubicación del stock</span>
            <span className="tabla__celda productos__col-estado">Estado</span>
            <span className="tabla__celda productos__col-acciones" />
          </div>
          <ul className="productos__lista" aria-label="Productos">
            {datos.items.map((f) => (
              <li key={f.id} className="tabla__fila">
                <span className="tabla__celda tabla__celda--flex tabla__producto">
                  <ImagenProducto url={f.miniatura} linea={f.lineaCodigo} className="tabla__miniatura" />
                  <span className="label-m">{f.nombre}</span>
                </span>
                <span className="tabla__celda productos__col-precio">{f.precio != null ? precio(f.precio) : f.precioMayorista != null ? precio(f.precioMayorista) : '—'}</span>
                <span className="tabla__celda productos__col-venta">{unidades.format(f.enVenta)} und</span>
                <span className="tabla__celda productos__col-modalidad">{MODALIDAD[f.modalidad]}</span>
                <span className="tabla__celda productos__col-ubicacion">{ubicaciones(f.stock)}</span>
                <span className="tabla__celda productos__col-estado">{badge(f)}</span>
                <span className="tabla__celda productos__col-acciones">
                  <Enlace to={`${RUTAS.productorProductos}/${f.id}`} className="productos__icono-boton" aria-label={`Editar ${f.nombre}`}>
                    <Icono src={iconoEditar} tamano={18} />
                  </Enlace>
                  <span className="productos__menu">
                    <button type="button" className="productos__icono-boton" aria-label={`Más acciones para ${f.nombre}`} aria-haspopup="menu" aria-expanded={menu === f.id} onClick={() => setMenu(menu === f.id ? null : f.id)}>
                      <Icono src={iconoMasVertical} tamano={18} />
                    </button>
                    {menu === f.id && (
                      <span className="productos__menu-lista" role="menu">
                        <Enlace role="menuitem" to={`${RUTAS.productorLotes}?producto=${f.id}`}>Registrar lote</Enlace>
                        {f.estado === 'PUBLICADO' && (
                          <>
                            <Enlace role="menuitem" to={`/productos/${f.id}`}>Ver en la tienda</Enlace>
                            <button type="button" role="menuitem" onClick={() => { setMenu(null); setConfirmar(f) }}>Despublicar</button>
                          </>
                        )}
                        {f.estado !== 'PUBLICADO' && (
                          <button type="button" role="menuitem" onClick={() => { setMenu(null); cambiarEstado(f, 'publicar') }}>
                            {f.estado === 'RETIRADO' ? 'Volver a publicar' : 'Publicar'}
                          </button>
                        )}
                      </span>
                    )}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <p className="productor__nota">
        <Icono src={iconoInfo} tamano={16} />
        Solo ves tus propios productos. “En venta” es el stock en puntos de acopio; lo que está en tu almacén se vende después de transferirlo.
      </p>

      <Dialogo
        abierto={confirmar !== null}
        titulo="¿Despublicar este producto?"
        confirmar="Despublicar"
        tipoConfirmar="destructivo"
        ocupado={ocupado}
        onCancelar={() => setConfirmar(null)}
        onConfirmar={() => cambiarEstado(confirmar, 'retirar')}
      >
        <p>
          <strong>{confirmar?.nombre}</strong> dejará de aparecer en el catálogo y en tu tienda. No se borra: sigue en los
          pedidos y en el historial, y puedes volver a publicarlo cuando quieras.
        </p>
      </Dialogo>
    </PaginaProductor>
  )
}
