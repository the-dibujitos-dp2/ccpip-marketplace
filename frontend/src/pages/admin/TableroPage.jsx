import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEstrella from '../../assets/icons/estrella.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoPorcentaje from '../../assets/icons/porcentaje.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import { obtenerTablero } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { mesAnterior, precio, unidades } from '../../formato.js'
import '../productor/productor.css'
import './admin.css'

/** CCPIP-430: color de cada nivel de merma (siempre con texto, nunca solo color). */
const TONO_MERMA = { NORMAL: 'exito', ALERTA: 'aviso', CRITICO: 'error' }

/**
 * SA-01 (CCPIP-111): la operacion del mes con informacion agregada y de solo lectura. La merma del mes se
 * colorea con los umbrales de cada linea (CCPIP-430). Calificaciones y reclamos aun no se registran en la
 * plataforma: se muestran sin dato, no con un numero inventado.
 */
export default function TableroPage({ path }) {
  const { t } = useTranslation()
  const [tablero, setTablero] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    obtenerTablero().then(setTablero).catch(() => setError(true))
  }, [])

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.tablero')} titulo={t('admin.tablero.titulo')}>
      {error && <Alerta tono="error" titulo={t('admin.tablero.error')} descripcion={t('comun.errorConexion')} />}
      {!tablero && !error && <p className="caption">{t('comun.cargando')}</p>}
      {tablero && <Contenido tablero={tablero} />}
    </LayoutAdmin>
  )
}

function Contenido({ tablero }) {
  const { t } = useTranslation()
  const { ventas, comision, productores } = tablero
  const anterior = mesAnterior(tablero.periodo)
  let variacion = { texto: t('admin.tablero.sinComparacion', { mes: anterior }), tono: 'neutro' }
  if (ventas.variacion != null) {
    const sube = ventas.variacion >= 0
    variacion = {
      texto: t('admin.tablero.variacion', { flecha: sube ? '▲' : '▼', valor: unidades(Math.abs(ventas.variacion)), mes: anterior }),
      tono: sube ? 'exito' : 'error',
    }
  }

  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('admin.tablero.kpi.ventas')} valor={precio(ventas.mes)} detalle={variacion.texto} tono={variacion.tono} icono={iconoGrafico} />
        {comision.porcentaje == null ? (
          <TarjetaKpi titulo={t('admin.tablero.kpi.comision')} valor="—" detalle={t('admin.tablero.kpi.sinComision')} tono="neutro" icono={iconoPorcentaje} />
        ) : (
          <TarjetaKpi titulo={t('admin.tablero.kpi.comision')} valor={precio(comision.estimada)} detalle={t('admin.tablero.kpi.vigente', { porcentaje: unidades(comision.porcentaje) })} icono={iconoPorcentaje} />
        )}
        <TarjetaKpi titulo={t('admin.tablero.kpi.productores')} valor={productores.activos} detalle={t('admin.tablero.kpi.nuevos', { count: productores.nuevosDelMes })} icono={iconoUsuario} />
        <TarjetaKpi titulo={t('admin.tablero.kpi.calificacion')} valor="—" detalle={t('admin.tablero.pronto')} tono="neutro" icono={iconoEstrella} />
      </div>

      <div className="admin__fila">
        <VentasPorLinea lineas={tablero.ventasPorLinea} />
        <ProductoresTop productores={tablero.productoresTop} />
      </div>

      <section className="productor__seccion" aria-labelledby="titulo-puntos">
        <h2 id="titulo-puntos" className="heading-h3">{t('admin.tablero.puntos.titulo')}</h2>
        {/* tabIndex: en un celular la tabla se desplaza de lado y debe poder hacerse con el teclado */}
        <div className="tabla" role="table" aria-labelledby="titulo-puntos" tabIndex={0}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.tablero.puntos.punto')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.tablero.puntos.stock')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.tablero.puntos.pedidos')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.tablero.puntos.ventas')}</span>
            <span className="tabla__celda admin__merma" role="columnheader">{t('admin.tablero.puntos.merma')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.tablero.puntos.reclamos')}</span>
          </div>
          {tablero.puntos.map((p) => (
            <div key={p.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex label-m" role="cell">{p.nombre} · {p.distrito}</span>
              <span className="tabla__celda admin__num" role="cell">{t('comun.und', { cantidad: unidades(p.stock) })}</span>
              <span className="tabla__celda admin__num" role="cell">{p.pedidosDelMes}</span>
              <span className="tabla__celda admin__num" role="cell">{precio(p.ventasDelMes)}</span>
              <span className="tabla__celda admin__merma" role="cell"><MermaPunto merma={p.merma} /></span>
              <span className="tabla__celda admin__num" role="cell" aria-label={t('admin.tablero.pronto')}>—</span>
            </div>
          ))}
        </div>
        <p className="productor__nota">{t('admin.tablero.merma.notaPunto')} {t('admin.tablero.notaPronto')}</p>
      </section>

      <MermaPorLinea filas={tablero.mermaPorLinea ?? []} />
    </>
  )
}

/** CCPIP-430: % de merma del mes con el color de su nivel. */
function NivelMerma({ porcentaje, nivel }) {
  const { t } = useTranslation()
  return (
    <Badge tono={TONO_MERMA[nivel]}>
      {t('admin.tablero.merma.valor', { porcentaje: unidades(porcentaje), nivel: t(`admin.tablero.merma.nivel.${nivel}`) })}
    </Badge>
  )
}

/** Un punto sin stock ni merma no tiene porcentaje: se muestra sin dato, no como 0 %. */
function MermaPunto({ merma }) {
  const { t } = useTranslation()
  if (merma?.porcentaje == null) return <span aria-label={t('admin.tablero.merma.sinDato')}>—</span>
  return <NivelMerma porcentaje={merma.porcentaje} nivel={merma.nivel} />
}

/** CCPIP-430: merma del mes de cada linea en cada punto, solo donde hubo merma. */
function MermaPorLinea({ filas }) {
  const { t } = useTranslation()
  return (
    <section className="productor__seccion" aria-labelledby="titulo-merma">
      <h2 id="titulo-merma" className="heading-h3">{t('admin.tablero.merma.titulo')}</h2>
      <p className="productor__nota">{t('admin.tablero.merma.intro')}</p>
      {filas.length === 0 ? (
        <p className="body-s">{t('admin.tablero.merma.vacio')}</p>
      ) : (
        <div className="tabla" role="table" aria-labelledby="titulo-merma" tabIndex={0}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.tablero.merma.punto')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.tablero.merma.linea')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.tablero.merma.unidades')}</span>
            <span className="tabla__celda admin__merma" role="columnheader">{t('admin.tablero.merma.porcentaje')}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t('admin.tablero.merma.umbrales')}</span>
          </div>
          {filas.map((m) => (
            <div key={`${m.puntoId}-${m.codigo}`} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex label-m" role="cell">{m.punto}</span>
              <span className="tabla__celda tabla__celda--flex" role="cell">{m.linea}</span>
              <span className="tabla__celda admin__num" role="cell">{t('comun.und', { cantidad: unidades(m.unidades) })}</span>
              <span className="tabla__celda admin__merma" role="cell"><NivelMerma porcentaje={m.porcentaje} nivel={m.nivel} /></span>
              <span className="tabla__celda admin__num" role="cell">
                {t('admin.tablero.merma.umbralesValor', { alerta: unidades(m.umbralAlerta), critico: unidades(m.umbralCritico) })}
              </span>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}

function VentasPorLinea({ lineas }) {
  const { t } = useTranslation()
  const maximo = Math.max(0, ...lineas.map((l) => Number(l.monto)))
  return (
    <section className="tarjeta-panel admin__lineas" aria-labelledby="titulo-lineas">
      <h2 id="titulo-lineas" className="heading-h3">{t('admin.tablero.lineas')}</h2>
      <ul className="admin__barras">
        {lineas.map((l) => {
          const ancho = maximo > 0 ? (Number(l.monto) / maximo) * 100 : 0
          return (
            <li key={l.codigo} className="admin__barra">
              <span className="body-s">{l.nombre}</span>
              <span className="admin__pista" aria-hidden="true">
                <span className="admin__relleno" style={{ width: `${ancho}%` }} />
              </span>
              <span className="label-m admin__num">{precio(l.monto)}</span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function ProductoresTop({ productores }) {
  const { t } = useTranslation()
  return (
    <section className="tarjeta-panel admin__top" aria-labelledby="titulo-top">
      <h2 id="titulo-top" className="heading-h3">{t('admin.tablero.top')}</h2>
      {productores.length === 0 ? (
        <p className="body-s">{t('admin.tablero.sinVentas')}</p>
      ) : (
        <ol className="admin__ranking">
          {productores.map((p, i) => (
            <li key={p.id} className="admin__puesto">
              <span className="admin__numero" aria-hidden="true">{i + 1}</span>
              <span className="admin__productor">
                <span className="label-m">{p.nombre}</span>
                {p.pueblo && <span className="caption">{p.pueblo}</span>}
              </span>
              <span className="label-m">{precio(p.monto)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
