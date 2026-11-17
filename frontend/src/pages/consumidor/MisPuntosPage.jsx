import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { canjearCupon, misPuntos } from '../../api/fidelizacion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import Rocco from '../../components/Rocco.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { textoBeneficio } from '../../fidelizacion.js'
import { diaMesAnioLargo, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import LateralCuenta from './LateralCuenta.jsx'
import './MisPedidosPage.css'
import './MisFavoritosPage.css'
import './MisPuntosPage.css'

const P = 'misPuntos'
const PESTANAS = ['canjear', 'cupones', 'movimientos']
const TONO_CUPON = { DISPONIBLE: 'exito', EN_USO: 'info', USADO: 'neutro', VENCIDO: 'neutro' }

/**
 * Mi cuenta › Mis puntos: el saldo (CCPIP-173) y sus movimientos (CCPIP-374), el nivel por recurrencia y cuanto falta
 * para el siguiente (CCPIP-88), los cupones del catalogo para canjear con puntos y los ya canjeados (CCPIP-89).
 */
export default function MisPuntosPage() {
  const { t } = useTranslation()
  const { datos, error, recargar, setDatos } = useConsulta(misPuntos)
  const [pestana, setPestana] = useState('canjear')
  const [aCanjear, setACanjear] = useState(null)
  const [canjeando, setCanjeando] = useState(false)
  const [aviso, setAviso] = useState(null)

  async function canjear() {
    setCanjeando(true)
    try {
      const nuevo = await canjearCupon(aCanjear.id)
      setDatos(nuevo)
      setAviso({ tono: 'exito', titulo: t(`${P}.canjeado`, { nombre: aCanjear.nombre, codigo: nuevo.cupones.find((c) => c.estado === 'DISPONIBLE')?.codigo }), descripcion: t(`${P}.canjeadoTexto`) })
      setPestana('cupones')
    } catch (e) {
      setAviso({ tono: 'error', titulo: t(`${P}.errorCanjear`), descripcion: mensajeError(e) })
    } finally {
      setCanjeando(false)
      setACanjear(null)
    }
  }

  return (
    <LayoutTienda>
      <main className="contenedor mis-pedidos">
        <h1 className="mis-pedidos__titulo">{t('misPedidos.miCuenta')}</h1>
        <div className="mis-pedidos__cuerpo">
          <LateralCuenta activo="puntos" />
          <section className="mis-pedidos__contenido" aria-labelledby="titulo-puntos" aria-busy={!datos && !error}>
            <h2 id="titulo-puntos" className="mis-favoritos__titulo">{t(`${P}.titulo`)}</h2>
            {error && (
              <EstadoVacio titulo={t(`${P}.error`)} descripcion={t('comun.errorConexion')}>
                <Boton tamano="M" onClick={recargar}>{t('carrito.reintentar')}</Boton>
              </EstadoVacio>
            )}
            {!error && !datos && <p className="visually-hidden">{t('comun.cargando')}</p>}
            {datos && (
              <>
                <Resumen datos={datos} />
                {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
                <Pestanas etiqueta={t(`${P}.titulo`)} valor={pestana} onCambiar={setPestana}
                  opciones={PESTANAS.map((k) => ({ valor: k, etiqueta: t(`${P}.pestana.${k}`) }))} />
                <div role="tabpanel" aria-label={t(`${P}.pestana.${pestana}`)}>
                  {pestana === 'canjear' && <Catalogo datos={datos} onCanjear={setACanjear} />}
                  {pestana === 'cupones' && <MisCupones cupones={datos.cupones} />}
                  {pestana === 'movimientos' && <Movimientos movimientos={datos.movimientos} />}
                </div>
              </>
            )}
          </section>
        </div>
      </main>
      <Dialogo abierto={Boolean(aCanjear)} titulo={t(`${P}.confirmarTitulo`)} confirmar={t(`${P}.confirmar`)} ocupado={canjeando}
        onConfirmar={canjear} onCancelar={() => setACanjear(null)}>
        {aCanjear && (
          <p>{t(`${P}.confirmarTexto`, { puntos: unidades(aCanjear.costoPuntos), nombre: aCanjear.nombre, saldo: unidades(datos.saldo - aCanjear.costoPuntos) })}</p>
        )}
      </Dialogo>
    </LayoutTienda>
  )
}

/** El saldo, el nivel y lo que falta para el siguiente. */
function Resumen({ datos }) {
  const { t } = useTranslation()
  const { nivel, niveles } = datos
  const avance = nivel.siguiente ? Math.round((nivel.pedidosEntregados / nivel.siguiente.pedidosMinimos) * 100) : 100
  return (
    <div className="mis-puntos__resumen">
      <Rocco variante="feliz" tamano={72} className="mis-puntos__rocco" />
      <div className="mis-puntos__saldo">
        <p className="caption">{t(`${P}.saldo`)}</p>
        <p className="mis-puntos__numero">{unidades(datos.saldo)}</p>
        <p className="body-s">{t(`${P}.comoSeGanan`)}</p>
      </div>
      {niveles.length > 0 && (
        <div className="mis-puntos__nivel">
          <p className="caption">{t(`${P}.nivel`)}</p>
          <p className="label-m">
            {nivel.actual ? t(`${P}.nivelActual`, { nombre: nivel.actual.nombre, multiplicador: unidades(nivel.actual.multiplicador) }) : t(`${P}.sinNivel`)}
          </p>
          {nivel.siguiente && (
            <>
              <div className="mis-puntos__barra" role="progressbar" aria-valuemin={0} aria-valuemax={nivel.siguiente.pedidosMinimos}
                aria-valuenow={nivel.pedidosEntregados} aria-label={t(`${P}.avance`, { count: nivel.faltan, nombre: nivel.siguiente.nombre })}>
                <span style={{ width: `${avance}%` }} />
              </div>
              <p className="caption">
                {t(`${P}.avance`, { count: nivel.faltan, nombre: nivel.siguiente.nombre })} · {t(`${P}.multiplica`, { multiplicador: unidades(nivel.siguiente.multiplicador) })}
              </p>
            </>
          )}
        </div>
      )}
    </div>
  )
}

function Catalogo({ datos, onCanjear }) {
  const { t } = useTranslation()
  if (!datos.catalogo.length) return <EstadoVacio titulo={t(`${P}.catalogoVacio`)} descripcion={t(`${P}.catalogoVacioTexto`)} rocco="pensativo" />
  return (
    <ul className="mis-favoritos__lista" aria-label={t(`${P}.pestana.canjear`)}>
      {datos.catalogo.map((c) => (
        <li key={c.id} className="mis-favoritos__item mis-puntos__cupon">
          <p className="mis-puntos__costo">{t(`${P}.puntos`, { puntos: unidades(c.costoPuntos) })}</p>
          <p className="mis-favoritos__nombre">{c.nombre}</p>
          <p className="body-s">{textoBeneficio(t, c)}</p>
          {c.descripcion && <p className="caption">{c.descripcion}</p>}
          <p className="caption">{t(`${P}.vigencia`, { count: c.vigenciaDias })}</p>
          <Boton tamano="S" anchoCompleto className="mis-favoritos__retirar" disabled={!c.alcanza} onClick={() => onCanjear(c)}
            aria-label={t(`${P}.canjearDe`, { nombre: c.nombre })}>
            {c.alcanza ? t(`${P}.canjear`) : t(`${P}.faltan`, { puntos: unidades(c.costoPuntos - datos.saldo) })}
          </Boton>
        </li>
      ))}
    </ul>
  )
}

function MisCupones({ cupones }) {
  const { t } = useTranslation()
  if (!cupones.length) return <EstadoVacio titulo={t(`${P}.sinCupones`)} descripcion={t(`${P}.sinCuponesTexto`)} rocco="pensativo" />
  return (
    <ul className="mis-puntos__lista" aria-label={t(`${P}.pestana.cupones`)}>
      {cupones.map((c) => (
        <li key={c.id} className="mis-puntos__fila">
          <div>
            <p className="label-m">{c.nombre}</p>
            <p className="body-s">{textoBeneficio(t, c)}</p>
            <p className="caption">
              {c.estado === 'USADO' || c.estado === 'EN_USO' ? t(`${P}.usadoEn`, { pedido: c.pedido }) : t(`${P}.vence`, { fecha: diaMesAnioLargo(c.venceEn) })}
            </p>
          </div>
          <div className="mis-puntos__fila-fin">
            <code className="mis-puntos__codigo">{c.codigo}</code>
            <Badge tono={TONO_CUPON[c.estado]}>{t(`${P}.estado.${c.estado}`)}</Badge>
          </div>
        </li>
      ))}
      <li className="caption mis-puntos__ayuda">{t(`${P}.comoUsar`)} <Enlace to={RUTAS.carrito}>{t(`${P}.irCarrito`)}</Enlace></li>
    </ul>
  )
}

function Movimientos({ movimientos }) {
  const { t } = useTranslation()
  if (!movimientos.length) return <EstadoVacio titulo={t(`${P}.sinMovimientos`)} descripcion={t(`${P}.comoSeGanan`)} rocco="pensativo" />
  return (
    <ul className="mis-puntos__lista" aria-label={t(`${P}.pestana.movimientos`)}>
      {movimientos.map((m, i) => (
        <li key={`${m.fecha}-${i}`} className="mis-puntos__fila">
          <div>
            <p className="label-m">{t(`${P}.movimiento.${m.tipo}`, { referencia: m.referencia })}</p>
            <p className="caption">
              {diaMesAnioLargo(m.fecha)}
              {m.campana && ` · ${t(`${P}.conCampana`, { campana: m.campana })}`}
              {m.nivel && ` · ${t(`${P}.conNivel`, { nivel: m.nivel })}`}
            </p>
          </div>
          <span className={`mis-puntos__puntos mis-puntos__puntos--${m.tipo === 'CANJE' ? 'menos' : 'mas'}`}>
            {m.tipo === 'CANJE' ? '−' : '+'}{unidades(m.puntos)}
          </span>
        </li>
      ))}
    </ul>
  )
}
