import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoBuscar from '../../assets/icons/buscar.svg'
import iconoEditar from '../../assets/icons/editar.svg'
import iconoInfo from '../../assets/icons/info-pequeno.svg'
import iconoMas from '../../assets/icons/mas.svg'
import iconoMasVertical from '../../assets/icons/mas-vertical.svg'
import { listarProductos, publicarProducto, retirarProducto } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Giro from '../../components/Giro.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import { precio, unidades } from '../../formato.js'
import { mensajeError, traducirFaltante } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './ProductosPage.css'

/** CCPIP-46: EN_MODERACION es lo que pidio publicar y espera la revision de la Camara. */
const ESTADOS = ['PUBLICADO', 'EN_MODERACION', 'BORRADOR', 'RETIRADO']

function badge(fila, t) {
  // CCPIP-332: la Camara no publico la oferta; el motivo se ve al editarla
  if (fila.alerta === 'PUBLICACION_RECHAZADA') return <Badge tono="error">{t('productor.productos.badge.rechazada')}</Badge>
  if (fila.estado === 'BORRADOR') return <Badge tono="neutro">{t('productor.productos.badge.borrador')}</Badge>
  if (fila.estado === 'RETIRADO') return <Badge tono="neutro">{t('productor.productos.badge.despublicado')}</Badge>
  if (fila.estado === 'EN_MODERACION') return <Badge tono="info">{t('productor.productos.badge.enRevision')}</Badge>
  if (fila.alerta === 'LOTE_POR_VENCER') return <Badge tono="aviso">{t('productor.productos.badge.porVencer')}</Badge>
  if (fila.alerta === 'SIN_STOCK') return <Badge tono="error">{t('productor.productos.badge.sinStock')}</Badge>
  if (fila.alerta === 'STOCK_BAJO') return <Badge tono="error">{t('productor.productos.badge.stockBajo')}</Badge>
  return <Badge tono="exito">{t('productor.productos.badge.publicado')}</Badge>
}

function ubicaciones(stock, t) {
  if (!stock.length) return '—'
  return stock.map((s) => `${s.tipo === 'ALMACEN_PRODUCTOR' ? t('productor.productos.almacen') : s.ubicacion.replace(/^Centro (Nacional|Regional) /, '')} ${unidades(s.cantidad)}`).join(' · ')
}

/** PW-03: los productos del productor por estado (CCPIP-160). Solo ve los suyos (RNF-05). */
export default function ProductosPage({ path, search }) {
  const { t } = useTranslation()
  const inicial = new URLSearchParams(search)
  const [estado, setEstado] = useState(inicial.get('estado') ?? 'PUBLICADO')
  const [q, setQ] = useState(inicial.get('q') ?? '')
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(false)
  const [menu, setMenu] = useState(null)
  const [confirmar, setConfirmar] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  // CCPIP-413: publicar sale del menu sin confirmacion; el aro queda en el boton de la fila mientras espera
  const [publicando, setPublicando] = useState(null)
  const [aviso, setAviso] = useState(inicial.get('enviado') ? { tono: 'exito', titulo: t('productor.productos.enviado'), descripcion: t('productor.productos.enviadoTexto') } : null)
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
    if (ocupado) return
    setOcupado(true)
    if (accion === 'publicar') setPublicando(fila.id)
    try {
      if (accion === 'retirar') await retirarProducto(fila.id)
      else await publicarProducto(fila.id)
      setAviso(
        accion === 'retirar'
          ? { tono: 'exito', titulo: t('productor.productos.despublicado'), descripcion: t('productor.productos.despublicadoTexto') }
          : { tono: 'exito', titulo: t('productor.productos.enviado'), descripcion: t('productor.productos.enviadoTexto') },
      )
      await cargar()
    } catch (e) {
      setAviso({ tono: 'error', titulo: mensajeError(e, 'productor.productos.errorEstado'), descripcion: e.body?.detalles?.map(traducirFaltante).join(' · ') })
    } finally {
      setOcupado(false)
      setPublicando(null)
      setConfirmar(null)
    }
  }

  const conteo = datos?.conteo ?? {}
  const paginacion = usePaginacionLocal(datos?.items ?? [], JSON.stringify([estado, q]))

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.productos')} titulo={t('productor.productos.titulo')}>
      <div className="productos__barra">
        <label className="productos__buscador">
          <Icono src={iconoBuscar} tamano={20} />
          <span className="visually-hidden">{t('productor.productos.buscar')}</span>
          <input type="search" placeholder={t('productor.productos.buscarPlaceholder')} value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
          {t('productor.panel.publicarProducto')}
        </Boton>
      </div>

      <Pestanas
        etiqueta={t('productor.productos.estadoEtiqueta')}
        valor={estado}
        onCambiar={(v) => {
          setEstado(v)
          setMenu(null)
        }}
        opciones={ESTADOS.map((e) => ({ valor: e, etiqueta: t(`productor.productos.pestana.${e}`, { total: conteo[e] ?? 0 }) }))}
      />

      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {error && <Alerta tono="error" titulo={t('productor.productos.error')} descripcion={t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}

      {datos?.items.length === 0 && (
        <EstadoVacio
          titulo={q ? t('productor.productos.noEncontramos', { q }) : t(`productor.productos.vacio.${estado}`)}
          descripcion={estado === 'PUBLICADO' && !q ? t('productor.panel.sinProductosTexto') : undefined}
        >
          {estado === 'PUBLICADO' && !q && (
            <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
              {t('productor.panel.publicarProducto')}
            </Boton>
          )}
        </EstadoVacio>
      )}

      {datos?.items.length > 0 && (
        <div className="tabla" ref={menuRef}>
          <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
            <span className="tabla__celda tabla__celda--flex">{t('productor.tabla.producto')}</span>
            <span className="tabla__celda productos__col-precio">{t('productor.productos.col.precio')}</span>
            <span className="tabla__celda productos__col-venta">{t('productor.productos.col.enVenta')}</span>
            <span className="tabla__celda productos__col-modalidad">{t('productor.productos.col.modalidad')}</span>
            <span className="tabla__celda productos__col-ubicacion">{t('productor.productos.col.ubicacion')}</span>
            <span className="tabla__celda productos__col-estado">{t('productor.lote.tabla.estado')}</span>
            <span className="tabla__celda productos__col-acciones" />
          </div>
          <ul className="productos__lista" aria-label={t('productor.nav.productos')}>
            {paginacion.filas.map((f) => (
              <li key={f.id} className="tabla__fila">
                <span className="tabla__celda tabla__celda--flex tabla__producto">
                  <ImagenProducto url={f.miniatura} linea={f.lineaCodigo} className="tabla__miniatura" />
                  <span className="label-m">{f.nombre}</span>
                </span>
                <span className="tabla__celda productos__col-precio">{f.precio != null ? precio(f.precio) : f.precioMayorista != null ? precio(f.precioMayorista) : '—'}</span>
                <span className="tabla__celda productos__col-venta">{t('comun.und', { cantidad: unidades(f.enVenta) })}</span>
                <span className="tabla__celda productos__col-modalidad">{t(`modalidad.${f.modalidad}`)}</span>
                <span className="tabla__celda productos__col-ubicacion">{ubicaciones(f.stock, t)}</span>
                <span className="tabla__celda productos__col-estado">{badge(f, t)}</span>
                <span className="tabla__celda productos__col-acciones">
                  <Enlace to={`${RUTAS.productorProductos}/${f.id}`} className="productos__icono-boton" aria-label={t('productor.productos.editar', { nombre: f.nombre })}>
                    <Icono src={iconoEditar} tamano={18} />
                  </Enlace>
                  <span className="productos__menu">
                    <button type="button" className="productos__icono-boton" aria-label={t('productor.productos.masAcciones', { nombre: f.nombre })} aria-haspopup="menu" aria-expanded={menu === f.id}
                      disabled={publicando === f.id} aria-busy={publicando === f.id || undefined} onClick={() => setMenu(menu === f.id ? null : f.id)}>
                      {publicando === f.id ? <Giro tamano={18} /> : <Icono src={iconoMasVertical} tamano={18} />}
                    </button>
                    {menu === f.id && (
                      <span className="productos__menu-lista" role="menu">
                        <Enlace role="menuitem" to={`${RUTAS.productorLotes}?producto=${f.id}`}>{t('productor.panel.registrarLote')}</Enlace>
                        <Enlace role="menuitem" to={`${RUTAS.productorProductos}/${f.id}/evidencias`}>{t('productor.productos.evidencias')}</Enlace>
                        <Enlace role="menuitem" to={`${RUTAS.productorProductos}/${f.id}/premios`}>{t('productor.productos.premios')}</Enlace>
                        {f.estado === 'PUBLICADO' && (
                          <>
                            <Enlace role="menuitem" to={`/productos/${f.id}`}>{t('productor.productos.verEnTienda')}</Enlace>
                            <button type="button" role="menuitem" onClick={() => { setMenu(null); setConfirmar(f) }}>{t('productor.productos.despublicar')}</button>
                          </>
                        )}
                        {(f.estado === 'BORRADOR' || f.estado === 'RETIRADO') && (
                          <button type="button" role="menuitem" onClick={() => { setMenu(null); void cambiarEstado(f, 'publicar') }}>
                            {f.estado === 'RETIRADO' ? t('productor.productos.volverPublicar') : t('productor.productos.publicar')}
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
      {datos?.items.length > 0 && <Paginacion {...paginacion} />}

      <p className="productor__nota">
        <Icono src={iconoInfo} tamano={16} />
        {t('productor.productos.nota')}
      </p>

      <Dialogo
        abierto={confirmar !== null}
        titulo={t('productor.productos.confirmarTitulo')}
        confirmar={t('productor.productos.despublicar')}
        tipoConfirmar="destructivo"
        ocupado={ocupado}
        onCancelar={() => setConfirmar(null)}
        onConfirmar={() => cambiarEstado(confirmar, 'retirar')}
      >
        <p>
          <strong>{confirmar?.nombre}</strong> {t('productor.productos.confirmarTexto')}
        </p>
      </Dialogo>
    </PaginaProductor>
  )
}
