import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoBillete from '../../assets/icons/billete.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoPorcentaje from '../../assets/icons/porcentaje.svg'
import { consultarLiquidacion } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import { fechaHora, precio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './LiquidacionesPage.css'

/**
 * CCPIP-92 (RF-70): el productor consulta su liquidacion por periodo. Bruto, comision y neto los calcula
 * el servidor con lo que se copio al confirmar cada pedido; la pantalla solo los muestra.
 */
export default function LiquidacionesPage({ path }) {
  const { t } = useTranslation()
  const [rango, setRango] = useState({ desde: '', hasta: '' })
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)

  const consultar = useCallback(async (filtro) => {
    setCargando(true)
    setError(null)
    try {
      const respuesta = await consultarLiquidacion(filtro)
      setDatos(respuesta)
      setRango({ desde: respuesta.desde, hasta: respuesta.hasta })
    } catch (e) {
      setError(e)
    } finally {
      setCargando(false)
    }
  }, [])

  useEffect(() => {
    consultar()
  }, [consultar])

  const cambiar = (campo) => (e) => {
    const nuevo = { ...rango, [campo]: e.target.value }
    setRango(nuevo)
    if (nuevo.desde && nuevo.hasta) consultar(nuevo)
  }

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.liquidaciones')} titulo={t('productor.liquidaciones.titulo')}>
      <div className="liquidaciones__filtros">
        <Campo etiqueta={t('productor.liquidaciones.desde')} type="date" value={rango.desde} max={rango.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('productor.liquidaciones.hasta')} type="date" value={rango.hasta} min={rango.desde || undefined} onChange={cambiar('hasta')} />
      </div>
      {error && <Alerta tono="error" titulo={t('productor.liquidaciones.error')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {cargando && !datos && <p className="caption">{t('comun.cargando')}</p>}
      {datos && <Resultado datos={datos} />}
    </PaginaProductor>
  )
}

function Resultado({ datos }) {
  const { t } = useTranslation()
  const { resumen, pedidos } = datos
  return (
    <>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('productor.liquidaciones.kpi.bruto')} valor={precio(resumen.bruto)} detalle={t('productor.liquidaciones.kpi.pedidos', { count: resumen.pedidos })} tono="neutro" icono={iconoGrafico} />
        <TarjetaKpi titulo={t('productor.liquidaciones.kpi.comision')} valor={precio(resumen.comision)} icono={iconoPorcentaje} />
        <TarjetaKpi titulo={t('productor.liquidaciones.kpi.neto')} valor={precio(resumen.neto)} icono={iconoBillete} />
      </div>
      {resumen.sinComision > 0 && (
        <Alerta tono="info" titulo={t('productor.liquidaciones.sinComision', { count: resumen.sinComision })} descripcion={t('productor.liquidaciones.sinComisionTexto')} />
      )}
      <section className="productor__seccion" aria-labelledby="liquidaciones-tabla">
        <h2 id="liquidaciones-tabla" className="heading-h3">{t('productor.liquidaciones.tabla')}</h2>
        {pedidos.length === 0 ? (
          <EstadoVacio titulo={t('productor.liquidaciones.vacio')} descripcion={t('productor.liquidaciones.vacioTexto')} />
        ) : (
          <div className="tabla" role="table" aria-labelledby="liquidaciones-tabla">
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda liquidaciones__pedido" role="columnheader">{t('productor.liquidaciones.col.pedido')}</span>
              <span className="tabla__celda liquidaciones__fecha" role="columnheader">{t('productor.liquidaciones.col.fecha')}</span>
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.liquidaciones.col.productos')}</span>
              <span className="tabla__celda liquidaciones__importe" role="columnheader">{t('productor.liquidaciones.col.bruto')}</span>
              <span className="tabla__celda liquidaciones__importe" role="columnheader">{t('productor.liquidaciones.col.comision')}</span>
              <span className="tabla__celda liquidaciones__importe" role="columnheader">{t('productor.liquidaciones.col.neto')}</span>
            </div>
            {pedidos.map((p) => (
              <div key={p.id} className="tabla__fila" role="row">
                <span className="tabla__celda liquidaciones__pedido label-m" role="cell">{p.numero}</span>
                <span className="tabla__celda liquidaciones__fecha" role="cell">{fechaHora(p.confirmadoEn)}</span>
                <span className="tabla__celda tabla__celda--flex" role="cell">
                  <ul className="liquidaciones__lineas">
                    {p.lineas.map((l) => (
                      <li key={l.productoId}>{t('productor.liquidaciones.linea', { producto: l.producto, cantidad: unidades(l.cantidad), precio: precio(l.precioUnitario) })}</li>
                    ))}
                  </ul>
                </span>
                <span className="tabla__celda liquidaciones__importe" role="cell">{precio(p.bruto)}</span>
                <span className="tabla__celda liquidaciones__importe" role="cell">
                  {p.porcentaje == null
                    ? t('productor.liquidaciones.sinComisionCelda')
                    : t('productor.liquidaciones.conPorcentaje', { monto: precio(p.comision), porcentaje: unidades(p.porcentaje) })}
                </span>
                <span className="tabla__celda liquidaciones__importe label-m" role="cell">{precio(p.neto)}</span>
              </div>
            ))}
          </div>
        )}
        <p className="caption">{t('productor.liquidaciones.nota')}</p>
      </section>
    </>
  )
}
