import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarMisPedidos } from '../../api/compra.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import SeguimientoPedido, { conSeguimiento, fechaDe } from '../../components/SeguimientoPedido.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { diaMes, precio } from '../../formato.js'
import { RUTAS } from '../../router.js'
import LateralCuenta from './LateralCuenta.jsx'
import './MisPedidosPage.css'

// CCPIP-320: el pedido a domicilio listo para despacho sigue en curso hasta que se entregue
const EN_CURSO = ['PENDIENTE_PAGO', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'LISTO_PARA_DESPACHO']
const RECIENTES = 3

const TONO_ESTADO = {
  PENDIENTE_PAGO: 'aviso',
  CONFIRMADO: 'info',
  EN_PREPARACION: 'info',
  LISTO_PARA_RECOJO: 'exito',
  LISTO_PARA_DESPACHO: 'info',
  ENTREGADO: 'marca',
  CANCELADO: 'neutro',
}

/**
 * CW-09 · Mi cuenta › Mis pedidos. CCPIP-112: las compras propias con el total pagado y su estado;
 * CCPIP-70 y CCPIP-340: en que etapa esta cada pedido en curso y cuando paso por cada una.
 * Solo lo que ya existe: sin codigo de recojo. Calificar y denunciar se hace en el detalle del pedido recibido (CCPIP-81, CCPIP-83).
 */
export default function MisPedidosPage() {
  const { t } = useTranslation()
  const [pedidos, setPedidos] = useState(null)
  const [error, setError] = useState(false)
  const [pestana, setPestana] = useState(null)

  const cargar = useCallback(() => {
    setError(false)
    listarMisPedidos()
      .then(setPedidos)
      .catch((e) => e.status !== 401 && setError(true))
  }, [])
  useEffect(cargar, [cargar])

  const enCurso = pedidos?.filter((p) => EN_CURSO.includes(p.estado)) ?? []
  const historial = pedidos?.filter((p) => !EN_CURSO.includes(p.estado)) ?? []
  // Sin pedidos en curso, se abre directo en el historial
  const activa = pestana ?? (enCurso.length === 0 && historial.length > 0 ? 'historial' : 'enCurso')

  return (
    <LayoutTienda>
      <main className="contenedor mis-pedidos">
        <h1 className="mis-pedidos__titulo">{t('misPedidos.miCuenta')}</h1>
        <div className="mis-pedidos__cuerpo">
          <LateralCuenta activo="pedidos" />

          <section className="mis-pedidos__contenido" aria-label={t('misPedidos.titulo')} aria-busy={!pedidos && !error}>
            {error && (
              <EstadoVacio titulo={t('misPedidos.error')} descripcion={t('comun.errorConexion')}>
                <Boton tamano="M" onClick={cargar}>{t('carrito.reintentar')}</Boton>
              </EstadoVacio>
            )}
            {!error && !pedidos && <p className="visually-hidden">{t('comun.cargando')}</p>}
            {pedidos?.length === 0 && (
              <EstadoVacio titulo={t('misPedidos.vacio')} descripcion={t('misPedidos.vacioTexto')}>
                <Boton tamano="M" to={RUTAS.buscar}>{t('misPedidos.explorar')}</Boton>
              </EstadoVacio>
            )}
            {pedidos?.length > 0 && (
              <>
                <Pestanas
                  etiqueta={t('misPedidos.titulo')}
                  valor={activa}
                  onCambiar={setPestana}
                  opciones={[
                    { valor: 'enCurso', etiqueta: t('misPedidos.enCurso', { count: enCurso.length }) },
                    { valor: 'historial', etiqueta: t('misPedidos.historial', { count: historial.length }) },
                  ]}
                />
                {activa === 'enCurso' ? <EnCurso pedidos={enCurso} historial={historial} /> : <Historial pedidos={historial} />}
              </>
            )}
          </section>
        </div>
      </main>
    </LayoutTienda>
  )
}

function EnCurso({ pedidos, historial }) {
  const { t } = useTranslation()
  const entregados = historial.filter((p) => p.estado === 'ENTREGADO').slice(0, RECIENTES)
  return (
    <div role="tabpanel" className="mis-pedidos__panel">
      {pedidos.length === 0 && <p className="body-m">{t('misPedidos.sinEnCurso')}</p>}
      {pedidos.map((p) => (
        <TarjetaPedido key={p.id} pedido={p} />
      ))}
      {entregados.length > 0 && (
        <section className="mis-pedidos__recientes" aria-labelledby="mis-pedidos-recientes">
          <h2 id="mis-pedidos-recientes" className="heading-h3">{t('misPedidos.recientes')}</h2>
          <ListaPedidos pedidos={entregados} />
        </section>
      )}
    </div>
  )
}

function Historial({ pedidos }) {
  const { t } = useTranslation()
  return (
    <div role="tabpanel" className="mis-pedidos__panel">
      {pedidos.length === 0 ? <p className="body-m">{t('misPedidos.sinHistorial')}</p> : <ListaPedidos pedidos={pedidos} />}
    </div>
  )
}

/** "15 oct · 4 productos de 3 comunidades · S/ 92.50" */
function Resumen({ pedido }) {
  const { t } = useTranslation()
  const productos = t('misPedidos.productos', { count: pedido.productos })
  const contenido = pedido.comunidades > 1 ? t('misPedidos.deComunidades', { productos, count: pedido.comunidades }) : productos
  return (
    <p className="mis-pedidos__resumen">
      {[diaMes(pedido.creadoEn), contenido, precio(pedido.total)].join(' · ')}
    </p>
  )
}

function EstadoPedido({ estado }) {
  const { t } = useTranslation()
  return <Badge tono={TONO_ESTADO[estado] ?? 'neutro'}>{t(`misPedidos.estado.${estado}`, { defaultValue: estado })}</Badge>
}

function TarjetaPedido({ pedido }) {
  const { t } = useTranslation()
  const punto = pedido.punto
  const aDomicilio = pedido.modalidad === 'ENVIO'
  return (
    <article className="mis-pedidos__tarjeta" aria-labelledby={`pedido-${pedido.id}`}>
      <header className="mis-pedidos__cabecera">
        <div>
          <h2 id={`pedido-${pedido.id}`} className="mis-pedidos__numero">{t('pedido.titulo', { numero: pedido.numero })}</h2>
          <Resumen pedido={pedido} />
        </div>
        <EstadoPedido estado={pedido.estado} />
      </header>

      {pedido.estado === 'PENDIENTE_PAGO' && (
        <Alerta tono="aviso" titulo={t('pedido.esperaPago')} descripcion={t('pedido.esperaPagoTexto')} />
      )}
      {conSeguimiento(pedido) && <SeguimientoPedido pedido={pedido} />}

      <div className="mis-pedidos__pie">
        <p className="mis-pedidos__punto">
          {aDomicilio ? (
            <>
              <span className="label-m">{t('misPedidos.aDomicilio')}</span>
              <span className="caption">{t('misPedidos.saleDe', { punto: punto.nombre })}</span>
            </>
          ) : (
            <>
              <span className="label-m">{punto.nombre}</span>
              <span className="caption">{[punto.direccion, punto.distrito].filter(Boolean).join(' · ')}</span>
            </>
          )}
        </p>
        <div className="mis-pedidos__acciones">
          {pedido.estado === 'PENDIENTE_PAGO' && (
            <Boton tamano="S" to={RUTAS.compra}>{t('misPedidos.completarPago')}</Boton>
          )}
          <Boton tipo="secundario" tamano="S" to={`/pedidos/${pedido.id}`} aria-label={t('misPedidos.verDetalleDe', { numero: pedido.numero })}>
            {t('misPedidos.verDetalle')}
          </Boton>
        </div>
      </div>
    </article>
  )
}

function ListaPedidos({ pedidos }) {
  const { t } = useTranslation()
  return (
    <ul className="mis-pedidos__lista">
      {pedidos.map((p) => {
        const entregado = fechaDe(p.historial, 'ENTREGADO')
        return (
          <li key={p.id} className="mis-pedidos__fila">
            <span className="mis-pedidos__fila-datos">
              <span className="label-m">{t('pedido.titulo', { numero: p.numero })}</span>
              <span className="caption">
                {p.estado === 'ENTREGADO' && entregado ? t('misPedidos.entregadoEl', { fecha: diaMes(entregado) }) : diaMes(p.creadoEn)}
                {' · '}
                {t('misPedidos.productos', { count: p.productos })} · {precio(p.total)}
              </span>
            </span>
            <EstadoPedido estado={p.estado} />
            <Boton tipo="terciario" tamano="S" to={`/pedidos/${p.id}`} aria-label={t('misPedidos.verDetalleDe', { numero: p.numero })}>
              {t('misPedidos.verDetalle')}
            </Boton>
          </li>
        )
      })}
    </ul>
  )
}
