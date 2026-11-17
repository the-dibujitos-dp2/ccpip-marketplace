import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCalendario from '../../../assets/icons/calendario.svg'
import iconoComunidad from '../../../assets/icons/comunidad.svg'
import iconoEstrella from '../../../assets/icons/estrella.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoPaquete from '../../../assets/icons/paquete.svg'
import iconoReloj from '../../../assets/icons/reloj.svg'
import iconoUsuario from '../../../assets/icons/usuario.svg'
import { metricasCampana, obtenerFidelizacion } from '../../../api/fidelizacion.js'
import Alerta from '../../../components/Alerta.jsx'
import Boton from '../../../components/Boton.jsx'
import Cargando from '../../../components/Cargando.jsx'
import LayoutAdmin from '../../../components/layout/LayoutAdmin.jsx'
import { BarrasComparativas, SerieTemporal } from '../../../components/reportes-visuales/index.jsx'
import { Kpis, variacion } from '../../../components/TablaReporte.jsx'
import { fecha, fechaLarga, precio, unidades } from '../../../formato.js'
import { mensajeError } from '../../../i18n/errores.js'
import { RUTAS } from '../../../router.js'
import '../../productor/productor.css'
import '../admin.css'
import { DialogoFinalizar, EstadoCampana, rutaCampana, TEXTOS, textoCondiciones, textoPeriodo, textoRegla, VistaPrevia } from './comun.jsx'
import './fidelizacion.css'

const M = `${TEXTOS}.metricas`

/**
 * CCPIP-86: una campana de puntos. Si ya empezo, sus metricas frente al periodo anterior de igual duracion, los
 * puntos otorgados y por acreditar, y su evolucion por dia; si aun no, lo que daria a pedidos de ejemplo.
 */
export default function CampanaPage({ path, id }) {
  const { t } = useTranslation()
  const [panel, setPanel] = useState(null)
  const [metricas, setMetricas] = useState(null)
  const [error, setError] = useState(null)
  const [finalizar, setFinalizar] = useState(false)
  const [aviso, setAviso] = useState(null)

  const cargar = () => obtenerFidelizacion().then((p) => {
    setPanel(p)
    const campana = p.campanas.find((c) => String(c.id) === String(id))
    if (campana && (campana.estado === 'ACTIVA' || campana.estado === 'FINALIZADA')) return metricasCampana(id).then(setMetricas)
    return null
  }).catch(setError)
  useEffect(() => {
    void cargar()
  }, [id])

  const campana = panel?.campanas.find((c) => String(c.id) === String(id))
  const titulo = campana?.nombre ?? t(`${TEXTOS}.titulo`)
  return (
    <LayoutAdmin path={path} migas={`${t('admin.nav.fidelizacion')} / ${titulo}`} titulo={titulo}>
      {error && <Alerta tono="error" titulo={t(`${M}.error`)} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {panel && !campana && <Alerta tono="error" titulo={t(`${TEXTOS}.form.error.noEncontrada`)} />}
      {!panel && !error && <Cargando />}
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {campana && (
        <>
          <Cabecera campana={campana} onFinalizar={() => setFinalizar(true)} />
          {campana.estado === 'PROGRAMADA' && (
            <div className="fidelizacion__formulario-grid">
              <Alerta tono="info" titulo={t(`${M}.programada`, { fecha: fechaLarga(campana.desde) })} descripcion={t(`${M}.programadaTexto`)} />
              <aside className="fidelizacion__lateral" aria-label={t(`${TEXTOS}.previa.titulo`)}>
                <VistaPrevia campana={campana} base={panel.reglaBase} />
              </aside>
            </div>
          )}
          {campana.estado === 'CANCELADA' && <Alerta tono="info" titulo={t(`${M}.cancelada`)} descripcion={t(`${M}.canceladaTexto`)} />}
          {!metricas && !error && (campana.estado === 'ACTIVA' || campana.estado === 'FINALIZADA') && <Cargando />}
          {metricas && <Metricas m={metricas} />}
        </>
      )}
      <DialogoFinalizar
        campana={finalizar ? campana : null}
        onListo={(c) => {
          setFinalizar(false)
          setMetricas(null)
          setAviso({ tono: 'exito', titulo: t(`${TEXTOS}.${c.estado === 'CANCELADA' ? 'cancelar' : 'finalizar'}.listo`, { nombre: c.nombre }) })
          void cargar()
        }}
        onError={(descripcion) => {
          setFinalizar(false)
          setAviso({ tono: 'error', titulo: t(`${TEXTOS}.errorFinalizar`), descripcion })
        }}
        onCerrar={() => setFinalizar(false)}
      />
    </LayoutAdmin>
  )
}

function Cabecera({ campana, onFinalizar }) {
  const { t } = useTranslation()
  const vigente = campana.estado === 'ACTIVA' || campana.estado === 'PROGRAMADA'
  return (
    <section className="tarjeta-panel fidelizacion__cabecera-campana" aria-label={t(`${TEXTOS}.detalle`)}>
      <div className="fidelizacion__cabecera-fila">
        <div className="fidelizacion__cabecera-datos">
          <EstadoCampana estado={campana.estado} />
          <p className="label-m">{textoRegla(t, campana)}</p>
          <p className="body-s">{textoPeriodo(t, campana)} · {textoCondiciones(t, campana)}</p>
          {campana.descripcion && <p className="body-s fidelizacion__descripcion">{campana.descripcion}</p>}
          <p className="caption">
            {t(`${TEXTOS}.registradaPor`, { nombre: campana.registradoPor })}
            {campana.finalizadoPor && ` · ${t(`${TEXTOS}.finalizadaPor`, { nombre: campana.finalizadoPor })}`}
          </p>
        </div>
        <div className="fidelizacion__acciones">
          <Boton tipo="terciario" tamano="M" to={RUTAS.adminFidelizacion}>{t(`${TEXTOS}.volver`)}</Boton>
          {campana.estado === 'PROGRAMADA' && <Boton tipo="secundario" tamano="M" to={`${rutaCampana(campana.id)}/editar`}>{t(`${TEXTOS}.editar`)}</Boton>}
          {vigente && (
            <Boton tipo="terciario" tamano="M" peligro onClick={onFinalizar}>
              {t(`${TEXTOS}.${campana.estado === 'ACTIVA' ? 'finalizar' : 'cancelar'}.boton`)}
            </Boton>
          )}
        </div>
      </div>
    </section>
  )
}

/** Resultados frente al periodo anterior, puntos y evolucion por dia. Solo cifras agregadas. */
function Metricas({ m }) {
  const { t } = useTranslation()
  const ventas = (dias) => dias.map((d) => ({ etiqueta: d.fecha, valor: d.ventas }))
  return (
    <>
      <section className="productor__seccion" aria-labelledby="campana-resultados">
        <h2 id="campana-resultados" className="heading-h3">{t(`${M}.resultados`)}</h2>
        <p className="body-s fidelizacion__nota">
          {t(`${M}.comparacion`, { desde: fecha(m.desde), hasta: fecha(m.hasta), anteriorDesde: fecha(m.anteriorDesde), anteriorHasta: fecha(m.anteriorHasta) })}
        </p>
        <Kpis textos={M} tarjetas={[
          { clave: 'pedidos', valor: unidades(m.pedidos.valor), icono: iconoPaquete, ...variacion(m.pedidos) },
          { clave: 'ventas', valor: precio(m.ventas.valor), icono: iconoGrafico, ...variacion(m.ventas) },
          { clave: 'ticket', valor: precio(m.ticketPromedio.valor), icono: iconoGrafico, ...variacion(m.ticketPromedio) },
          { clave: 'clientes', valor: unidades(m.clientes.valor), icono: iconoUsuario, ...variacion(m.clientes) },
        ]} />
      </section>
      <section className="productor__seccion" aria-labelledby="campana-puntos">
        <h2 id="campana-puntos" className="heading-h3">{t(`${M}.puntosTitulo`)}</h2>
        <Kpis textos={M} tarjetas={[
          { clave: 'otorgados', valor: unidades(m.puntosOtorgados), icono: iconoEstrella, vars: { count: m.pedidosEntregados } },
          { clave: 'porAcreditar', valor: unidades(m.puntosPorAcreditar), icono: iconoReloj, tono: m.pedidosPorEntregar ? 'aviso' : 'neutro', vars: { count: m.pedidosPorEntregar } },
          { clave: 'nuevos', valor: unidades(m.clientesNuevos), icono: iconoComunidad, vars: { count: m.clientesNuevos } },
          { clave: 'recurrentes', valor: unidades(m.clientesRecurrentes), icono: iconoCalendario, vars: { count: m.clientesRecurrentes } },
        ]} />
        {m.pedidosBajoMinimo > 0 && (
          <Alerta tono="info" titulo={t(`${M}.bajoMinimo`, { count: m.pedidosBajoMinimo, monto: precio(m.campana.montoMinimo) })}
            descripcion={t(`${M}.bajoMinimoTexto`)} />
        )}
      </section>
      <section className="productor__seccion fidelizacion__graficos" aria-label={t(`${M}.evolucion`)}>
        <SerieTemporal titulo={t(`${M}.ventasPorDia`)} unidad="S/" series={[{ id: 'ventas', nombre: t(`${M}.ventas`), puntos: ventas(m.dias) }]} />
        <SerieTemporal titulo={t(`${M}.puntosPorDia`)} unidad=""
          series={[{ id: 'puntos', nombre: t(`${M}.puntosSerie`), puntos: m.dias.map((d) => ({ etiqueta: d.fecha, valor: d.puntos })) }]} />
      </section>
      <section className="productor__seccion" aria-label={t(`${M}.productos`)}>
        <BarrasComparativas titulo={t(`${M}.productos`)} unidad="S/"
          items={m.productos.map((p) => ({ id: p.id, etiqueta: `${p.nombre} · ${p.comunidad}`, valor: p.ventas }))} />
      </section>
    </>
  )
}
