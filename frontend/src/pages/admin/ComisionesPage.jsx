import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoBillete from '../../assets/icons/billete.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoPorcentaje from '../../assets/icons/porcentaje.svg'
import { comisionesPorPedido } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import FiltroFechas from '../../components/FiltroFechas.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaHora, precio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'

/** Estados que se pueden filtrar; un pedido cancelado no aparece en esta consulta. */
const ESTADOS = ['CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'LISTO_PARA_DESPACHO', 'EN_CAMINO', 'ENTREGADO']

const SIN_FILTROS = { desde: '', hasta: '', productor: '', estado: '', pagina: 0 }

/**
 * CCPIP-91 (RF-69): la administracion de la Camara (super y sub administradores) consulta la comision y
 * el neto de cada pedido confirmado, tal como se copiaron al confirmar. Sin datos del comprador.
 * CCPIP-431: la lista viene paginada del servidor y se filtra por productor y estado; los totales son
 * de todo lo filtrado. Cambiar un filtro vuelve a la primera pagina.
 */
export default function ComisionesPage({ path }) {
  const { t } = useTranslation()
  // Lo que se envia al servidor. Las fechas quedan vacias hasta que se cambian: el servidor usa el mes en curso
  const [filtros, setFiltros] = useState(SIN_FILTROS)
  // Lo que se escribio en cada fecha (null: aun no se toco), que puede estar a medio escribir
  const [escrito, setEscrito] = useState({ desde: null, hasta: null })
  const consultar = useCallback(() => comisionesPorPedido(filtros), [filtros])
  const { datos, error } = useConsulta(consultar)
  // Una fecha que no se toco muestra la que uso el servidor (el mes en curso en la primera carga)
  const rango = { desde: escrito.desde ?? datos?.desde ?? '', hasta: escrito.hasta ?? datos?.hasta ?? '' }

  const cambiarFecha = (campo) => (e) => {
    const nuevo = { ...rango, [campo]: e.target.value }
    setEscrito(nuevo)
    if (nuevo.desde && nuevo.hasta) setFiltros((f) => ({ ...f, ...nuevo, pagina: 0 }))
  }
  const cambiarFiltro = (campo) => (e) => setFiltros((f) => ({ ...f, [campo]: e.target.value, pagina: 0 }))
  const limpiar = () => setFiltros((f) => ({ ...f, productor: '', estado: '', pagina: 0 }))
  const filtrado = Boolean(filtros.productor || filtros.estado)

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.comisiones')} titulo={t('admin.comisiones.titulo')}>
      <FiltroFechas className="comisiones__filtros" rango={rango} cambiar={cambiarFecha}
        etiquetaDesde={t('admin.comisiones.desde')} etiquetaHasta={t('admin.comisiones.hasta')}>
        <Campo tipo="selector" etiqueta={t('admin.comisiones.filtro.productor')} placeholder={t('admin.comisiones.filtro.todos')}
          value={filtros.productor} onChange={cambiarFiltro('productor')}
          opciones={(datos?.productores ?? []).map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))} />
        <Campo tipo="selector" etiqueta={t('admin.comisiones.filtro.estado')} placeholder={t('admin.comisiones.filtro.todos')}
          value={filtros.estado} onChange={cambiarFiltro('estado')}
          opciones={ESTADOS.map((e) => ({ valor: e, etiqueta: t(`admin.comisiones.estado.${e}`) }))} />
        {filtrado && (
          <Boton tipo="terciario" tamano="M" className="comisiones__limpiar" onClick={limpiar}>{t('admin.comisiones.filtro.limpiar')}</Boton>
        )}
      </FiltroFechas>
      {error && <Alerta tono="error" titulo={t('admin.comisiones.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos && <Resultado datos={datos} filtrado={filtrado} onPagina={(pagina) => setFiltros((f) => ({ ...f, pagina }))} />}
    </LayoutAdmin>
  )
}

function Resultado({ datos, filtrado, onPagina }) {
  const { t } = useTranslation()
  const { resumen, pedidos } = datos
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('admin.comisiones.kpi.ventas')} valor={precio(resumen.ventas)} detalle={t('admin.comisiones.kpi.pedidos', { count: resumen.pedidos })} tono="neutro" icono={iconoGrafico} />
        <TarjetaKpi titulo={t('admin.comisiones.kpi.comision')} valor={precio(resumen.comision)} icono={iconoPorcentaje} />
        <TarjetaKpi titulo={t('admin.comisiones.kpi.neto')} valor={precio(resumen.neto)} icono={iconoBillete} />
      </div>
      {resumen.sinComision > 0 && (
        <Alerta tono="info" titulo={t('admin.comisiones.sinComision', { count: resumen.sinComision })} descripcion={t('admin.comisiones.sinComisionTexto')} />
      )}
      <section className="productor__seccion" aria-labelledby="comisiones-tabla">
        <h2 id="comisiones-tabla" className="heading-h3">{t('admin.comisiones.tabla')}</h2>
        {pedidos.length === 0 ? (
          <p className="body-s">{t(filtrado ? 'admin.comisiones.vacioFiltros' : 'admin.comisiones.vacio')}</p>
        ) : (
          <div className="tabla" role="table" aria-labelledby="comisiones-tabla">
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda comisiones__pedido" role="columnheader">{t('admin.comisiones.col.pedido')}</span>
              <span className="tabla__celda comisiones__fecha" role="columnheader">{t('admin.comisiones.col.fecha')}</span>
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.comisiones.col.productores')}</span>
              <span className="tabla__celda admin__num" role="columnheader">{t('admin.comisiones.col.venta')}</span>
              <span className="tabla__celda comisiones__comision" role="columnheader">{t('admin.comisiones.col.comision')}</span>
              <span className="tabla__celda admin__num" role="columnheader">{t('admin.comisiones.col.neto')}</span>
            </div>
            {pedidos.map((p) => (
              <div key={p.id} className="tabla__fila" role="row">
                <span className="tabla__celda comisiones__pedido" role="cell">
                  <span className="label-m">{p.numero}</span>
                  <span className="caption">{t(`admin.comisiones.estado.${p.estado}`, { defaultValue: p.estado })}</span>
                </span>
                <span className="tabla__celda comisiones__fecha" role="cell">{fechaHora(p.confirmadoEn)}</span>
                <span className="tabla__celda tabla__celda--flex" role="cell">
                  <ul className="comisiones__productores">
                    {p.productores.map((pr) => (
                      <li key={pr.productorId}>
                        <span>{pr.productor}</span>
                        {p.productores.length > 1 && pr.neto != null && (
                          <span className="caption">{t('admin.comisiones.netoProductor', { venta: precio(pr.venta), neto: precio(pr.neto) })}</span>
                        )}
                      </li>
                    ))}
                  </ul>
                </span>
                <span className="tabla__celda admin__num" role="cell">{precio(p.venta)}</span>
                <span className="tabla__celda comisiones__comision" role="cell">
                  {p.comision == null
                    ? t('admin.comisiones.sinComisionCelda')
                    : t('admin.comisiones.conPorcentaje', { monto: precio(p.comision), porcentaje: unidades(p.porcentaje) })}
                </span>
                <span className="tabla__celda admin__num" role="cell">{p.neto == null ? '—' : precio(p.neto)}</span>
              </div>
            ))}
          </div>
        )}
        <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} onCambiar={onPagina} />
        <p className="caption">{t('admin.comisiones.nota')}</p>
      </section>
    </>
  )
}
