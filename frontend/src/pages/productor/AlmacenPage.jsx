import { Fragment, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoGrafico from '../../assets/icons/grafico.svg'
import { marcarMermaAlmacen } from '../../api/merma.js'
import { consultarInventario, registrarMovimientoAlmacen } from '../../api/productor.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoMerma from '../../components/DialogoMerma.jsx'
import DialogoMovimiento, { AvisoRegistrado } from '../../components/DialogoMovimiento.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import { fechaCorta, mesAnio, unidades } from '../../formato.js'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import { MOTIVOS_ALMACEN } from '../../movimientos.js'
import { RUTAS } from '../../router.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './AlmacenPage.css'

const TONO = { DISPONIBLE: 'exito', POR_VENCER: 'aviso', VENCIDO: 'error' }
const POR_PRODUCTO = 'productos'

/** "Centro Regional Ucayali" -> "Ucayali", como en PW-07 */
const corto = (nombre) => nombre.replace(/^Centro (Nacional|Regional) /, '')

/**
 * PW-16 · CCPIP-136 (RF-110) y CCPIP-162 (RF-131): el almacen virtual unico del productor, por
 * ubicacion, lote y estado, y el total de cada producto. Solo consulta: el servidor envia unicamente
 * sus productos (RNF-05) y cada cantidad sale de sus lotes. CCPIP-283 (RF-203): registra entradas y
 * salidas en los lotes de su almacen, el unico que tiene en el MVP. Las transferencias (PW-17, PW-18)
 * y el mapa llegan con sus historias, por eso no hay botones para transferir.
 */
export default function AlmacenPage({ path }) {
  const { t } = useTranslation()
  const { datos, error, recargar } = useConsulta(consultarInventario)
  const [elegida, setElegida] = useState(null)
  const [avisoVisible, setAvisoVisible] = useState(true)
  const [dialogo, setDialogo] = useState(null)
  const [registrado, setRegistrado] = useState(null)
  // CCPIP-76: marcar como merma un lote de su almacen
  const [merma, setMerma] = useState(null)
  const [marcada, setMarcada] = useState(null)

  const ubicaciones = datos?.ubicaciones ?? []
  const productos = datos?.productos ?? []
  // Por defecto su almacen, como en el Figma
  const pestana = elegida ?? (ubicaciones[0] ? String(ubicaciones[0].id) : POR_PRODUCTO)
  const lotesDe = (u) => productos.flatMap((p) => p.lotes.filter((l) => l.ubicacionId === u.id).map((l) => ({ ...l, producto: p.nombre })))
  // Lo que el formulario ofrece: los lotes de su almacen, no los que ya estan en un punto
  const lotesAlmacen = productos.flatMap((p) => p.lotes.filter((l) => l.tipoUbicacion === 'ALMACEN_PRODUCTOR').map((l) => ({
    id: l.id, codigo: l.codigo, productoId: p.id, producto: p.nombre, stock: l.cantidad,
    fechaVencimiento: l.fechaVencimiento, vencido: l.estado === 'VENCIDO',
  })))

  function alRegistrar(r) {
    setDialogo(null)
    setRegistrado(r)
    recargar()
  }

  function alMarcar(r) {
    setMerma(null)
    setMarcada(r)
    recargar()
  }

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.almacen')} titulo={t('productor.almacen.titulo')}>
      {error && <Alerta tono="error" titulo={t('productor.almacen.error')} descripcion={t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}

      {datos && (
        <>
          <Indicadores resumen={datos.resumen} diasAviso={datos.diasAviso} />

          {datos.resumen.lotesVencidos > 0 && (
            <Alerta
              tono="aviso"
              titulo={t('productor.almacen.vencidos', { count: datos.resumen.lotesVencidos })}
              descripcion={t('productor.almacen.vencidosTexto')}
            />
          )}

          <Pestanas
            etiqueta={t('productor.almacen.ubicaciones')}
            valor={pestana}
            onCambiar={setElegida}
            opciones={[
              ...ubicaciones.map((u) => ({ valor: String(u.id), etiqueta: `${nombreUbicacion(u, t)} (${lotesDe(u).length})` })),
              { valor: POR_PRODUCTO, etiqueta: t('productor.almacen.porProducto') },
            ]}
          />

          {pestana === POR_PRODUCTO ? (
            <PorProducto productos={productos} />
          ) : (
            ubicaciones.filter((u) => String(u.id) === pestana).map((u) => (
              <PorUbicacion
                key={u.id}
                ubicacion={u}
                lotes={lotesDe(u)}
                onMovimiento={lotesAlmacen.length > 0 ? () => setDialogo({ clave: Date.now() }) : undefined}
                onMerma={(l) => { setMarcada(null); setMerma(l) }}
              />
            ))
          )}

          {registrado && <AvisoRegistrado registrado={registrado} onCerrar={() => setRegistrado(null)} />}
          {marcada && (
            <Alerta
              tono="exito"
              titulo={t('merma.marcada', { cantidad: unidades(marcada.merma.cantidad), lote: marcada.merma.lote })}
              descripcion={t('merma.marcadaSaldo', { saldo: unidades(marcada.saldo) })}
              onCerrar={() => setMarcada(null)}
            />
          )}

          {avisoVisible && (
            <Alerta
              tono="info"
              titulo={t('productor.almacen.unSoloInventario')}
              descripcion={t('productor.almacen.unSoloInventarioTexto')}
              onCerrar={() => setAvisoVisible(false)}
            />
          )}
        </>
      )}

      {merma && (
        <DialogoMerma
          lote={{ id: merma.id, codigo: merma.codigo, producto: merma.producto, detalle: t('productor.lote.miAlmacen'), stock: merma.cantidad }}
          marcar={marcarMermaAlmacen}
          onMarcada={alMarcar}
          onCancelar={() => setMerma(null)}
        />
      )}

      {dialogo && (
        <DialogoMovimiento
          key={dialogo.clave}
          lotes={lotesAlmacen}
          motivos={MOTIVOS_ALMACEN}
          registrar={registrarMovimientoAlmacen}
          onRegistrado={alRegistrar}
          onCancelar={() => setDialogo(null)}
        />
      )}
    </PaginaProductor>
  )
}

function nombreUbicacion(u, t) {
  return u.tipo === 'ALMACEN_PRODUCTOR' ? t('productor.lote.miAlmacen') : u.nombre
}

/** Los indicadores de PW-16, sin "Transferencias en curso": llega con PW-17. */
function Indicadores({ resumen, diasAviso }) {
  const { t } = useTranslation()
  const porPunto = resumen.porPunto.map((p) => `${corto(p.ubicacion)} ${unidades(p.cantidad)}`).join(' · ')
  return (
    <div className="productor__kpis">
      <TarjetaKpi
        titulo={t('productor.almacen.kpi.enAlmacen')}
        valor={t('comun.und', { cantidad: unidades(resumen.enAlmacen) })}
        detalle={t('productor.almacen.kpi.productos', { count: resumen.productosEnAlmacen })}
        icono={iconoGrafico}
      />
      <TarjetaKpi
        titulo={t('productor.almacen.kpi.porVencer')}
        valor={t('productor.almacen.kpi.lotes', { count: resumen.lotesPorVencer })}
        detalle={t('productor.almacen.kpi.proximos', { dias: diasAviso })}
        // CCPIP-426: amarillo de advertencia
        tono="aviso"
        icono={iconoGrafico}
      />
      <TarjetaKpi
        titulo={t('productor.almacen.kpi.enAcopios')}
        valor={t('comun.und', { cantidad: unidades(resumen.enPuntosAcopio) })}
        detalle={porPunto || t('productor.almacen.kpi.sinAcopios')}
        tono={porPunto ? 'exito' : 'neutro'}
        icono={iconoGrafico}
      />
    </div>
  )
}

/** "Cosecha setiembre de 2026 · vence 15/03/2027", o lo que se sepa del lote. */
function cosechaYVencimiento(l, t) {
  const vence = l.fechaVencimiento ? t('productor.almacen.vence', { fecha: fechaCorta(l.fechaVencimiento) }) : t('productor.almacen.sinVencimiento')
  return l.fechaProduccion ? `${t('productor.almacen.cosecha', { mes: mesAnio(l.fechaProduccion) })} · ${vence}` : vence
}

/** Los lotes con saldo en una ubicacion (CCPIP-162). En su almacen, la accion de CCPIP-283. */
function PorUbicacion({ ubicacion, lotes, onMovimiento, onMerma }) {
  const { t } = useTranslation()
  const paginas = usePaginacionLocal(lotes, ubicacion.id)
  const almacen = ubicacion.tipo === 'ALMACEN_PRODUCTOR'
  const titulo = almacen
    ? t('productor.almacen.inventarioAlmacen', { departamento: ubicacion.departamento })
    : t('productor.almacen.inventarioPunto', { nombre: ubicacion.nombre })
  return (
    <section className="productor__seccion" aria-labelledby="titulo-inventario">
      <div className="productor__encabezado">
        <h2 id="titulo-inventario" className="heading-h3">{titulo}</h2>
        {almacen && onMovimiento && <Boton tamano="M" onClick={onMovimiento}>{t('movimiento.titulo')}</Boton>}
      </div>
      {lotes.length === 0 ? (
        <EstadoVacio titulo={t(almacen ? 'productor.almacen.vacioAlmacen' : 'productor.almacen.vacioPunto')} descripcion={t('productor.almacen.vacioTexto')}>
          <Boton tipo="secundario" tamano="M" to={RUTAS.productorLotes}>{t('productor.panel.registrarLote')}</Boton>
        </EstadoVacio>
      ) : (
        <div className="tabla" role="table" aria-label={titulo}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.tabla.producto')}</span>
            <span className="tabla__celda almacen__col-fechas" role="columnheader">{t('productor.almacen.col.fechas')}</span>
            <span className="tabla__celda almacen__col-cantidad" role="columnheader">
              {t(almacen ? 'productor.almacen.col.enAlmacen' : 'productor.almacen.col.enPunto')}
            </span>
            <span className="tabla__celda almacen__col-estado" role="columnheader">{t('productor.lote.tabla.estado')}</span>
            {almacen && <span className="tabla__celda almacen__col-accion" role="columnheader">{t('productor.tabla.accion')}</span>}
          </div>
          {paginas.filas.map((l) => (
            <div key={l.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m almacen__producto">{l.producto}</span>
                <span className="caption">{t('productor.almacen.lote', { codigo: l.codigo })}</span>
              </span>
              <span className="tabla__celda almacen__col-fechas" role="cell">{cosechaYVencimiento(l, t)}</span>
              <span className="tabla__celda almacen__col-cantidad" role="cell">{t('comun.und', { cantidad: unidades(l.cantidad) })}</span>
              <span className="tabla__celda almacen__col-estado" role="cell">
                <Badge tono={TONO[l.estado]}>{t(`productor.almacen.estado.${l.estado}`)}</Badge>
              </span>
              {almacen && (
                <span className="tabla__celda almacen__col-accion" role="cell">
                  {Number(l.cantidad) > 0 && (
                    <Boton tipo="terciario" tamano="S" onClick={() => onMerma(l)} aria-label={t('merma.marcarDe', { lote: l.codigo })}>
                      {t('merma.accion')}
                    </Boton>
                  )}
                </span>
              )}
            </div>
          ))}
        </div>
      )}
      <Paginacion {...paginas} />
    </section>
  )
}

/** El almacen virtual unico (CCPIP-136): el total de cada producto y donde esta. */
function PorProducto({ productos }) {
  const { t } = useTranslation()
  // CCPIP-427: el producto cuyo reparto por almacen esta desplegado
  const [abierto, setAbierto] = useState(null)
  const paginas = usePaginacionLocal(productos)
  const titulo = t('productor.almacen.inventarioProductos')
  return (
    <section className="productor__seccion" aria-labelledby="titulo-inventario">
      <h2 id="titulo-inventario" className="heading-h3">{titulo}</h2>
      {productos.length === 0 ? (
        <EstadoVacio titulo={t('productor.almacen.vacioProductos')} descripcion={t('productor.almacen.vacioTexto')}>
          <Boton tipo="secundario" tamano="M" to={RUTAS.productorLotes}>{t('productor.panel.registrarLote')}</Boton>
        </EstadoVacio>
      ) : (
        <div className="tabla" role="table" aria-label={titulo}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.tabla.producto')}</span>
            <span className="tabla__celda almacen__col-ubicaciones" role="columnheader">{t('productor.almacen.col.porUbicacion')}</span>
            <span className="tabla__celda almacen__col-lotes" role="columnheader">{t('productor.almacen.col.lotes')}</span>
            <span className="tabla__celda almacen__col-cantidad" role="columnheader">{t('productor.almacen.col.disponible')}</span>
            <span className="tabla__celda almacen__col-detalle" role="columnheader"><span className="visually-hidden">{t('productor.almacen.col.detalle')}</span></span>
          </div>
          {paginas.filas.map((p) => (
            <Fragment key={p.id}>
            <div className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex tabla__producto" role="cell">
                <ImagenProducto url={p.miniatura} className="tabla__miniatura" />
                <span className="label-m">{p.nombre}</span>
              </span>
              <span className="tabla__celda almacen__col-ubicaciones" role="cell">
                {p.porUbicacion.length
                  ? p.porUbicacion
                    .map((e) => `${e.tipo === 'ALMACEN_PRODUCTOR' ? t('productor.lote.miAlmacen') : corto(e.ubicacion)} ${unidades(e.cantidad)}`)
                    .join(' · ')
                  : t('productor.almacen.soloVencidos')}
              </span>
              <span className="tabla__celda almacen__col-lotes" role="cell">{p.lotes.length}</span>
              <span className="tabla__celda almacen__col-cantidad label-m" role="cell">{t('comun.und', { cantidad: unidades(p.total) })}</span>
              <span className="tabla__celda almacen__col-detalle" role="cell">
                <Boton
                  tipo="terciario"
                  tamano="S"
                  aria-expanded={abierto === p.id}
                  aria-controls={`almacenes-${p.id}`}
                  aria-label={t(abierto === p.id ? 'productor.almacen.ocultarAlmacenesDe' : 'productor.almacen.verAlmacenesDe', { producto: p.nombre })}
                  onClick={() => setAbierto(abierto === p.id ? null : p.id)}
                >
                  {t(abierto === p.id ? 'productor.almacen.ocultarAlmacenes' : 'productor.almacen.verAlmacenes')}
                </Boton>
              </span>
            </div>
            {abierto === p.id && (
              <div className="tabla__fila almacen__detalle-fila" role="row">
                <div className="tabla__celda almacen__detalle" role="cell" id={`almacenes-${p.id}`}>
                  <AlmacenesDe producto={p} />
                </div>
              </div>
            )}
            </Fragment>
          ))}
        </div>
      )}
      <Paginacion {...paginas} />
    </section>
  )
}

/**
 * CCPIP-427: en que almacenes esta un producto y cuanto hay en cada uno, para decidir donde reponer
 * o desde donde vender. Solo cuenta lo vigente, igual que el total de la fila; los lotes vencidos se
 * mencionan aparte porque no estan disponibles.
 */
function AlmacenesDe({ producto }) {
  const { t } = useTranslation()
  const vigentes = (u) => producto.lotes.filter((l) => l.ubicacionId === u && l.estado !== 'VENCIDO').length
  const vencidos = producto.lotes.filter((l) => l.estado === 'VENCIDO').length
  const titulo = t('productor.almacen.almacenesDe', { producto: producto.nombre })
  return (
    <>
      {producto.porUbicacion.length === 0 ? (
        <p className="body-s">{t('productor.almacen.sinDisponible')}</p>
      ) : (
        <div className="almacen__subtabla" role="table" aria-label={titulo}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.almacen.col.almacen')}</span>
            <span className="tabla__celda almacen__col-lotes" role="columnheader">{t('productor.almacen.col.lotes')}</span>
            <span className="tabla__celda almacen__col-cantidad" role="columnheader">{t('productor.almacen.col.disponible')}</span>
          </div>
          {producto.porUbicacion.map((e) => (
            <div key={e.ubicacionId} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{e.ubicacion}</span>
                <span className="caption almacen__tipo">
                  {t(e.tipo === 'ALMACEN_PRODUCTOR' ? 'productor.almacen.tipoAlmacen' : 'productor.almacen.tipoPunto')}
                </span>
              </span>
              <span className="tabla__celda almacen__col-lotes" role="cell">{vigentes(e.ubicacionId)}</span>
              <span className="tabla__celda almacen__col-cantidad label-m" role="cell">{t('comun.und', { cantidad: unidades(e.cantidad) })}</span>
            </div>
          ))}
        </div>
      )}
      {vencidos > 0 && <p className="productor__nota">{t('productor.almacen.vencidosAparte', { count: vencidos })}</p>}
    </>
  )
}
