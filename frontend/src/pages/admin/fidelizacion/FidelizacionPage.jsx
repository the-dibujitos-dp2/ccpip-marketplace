import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCalendario from '../../../assets/icons/calendario.svg'
import iconoEstrella from '../../../assets/icons/estrella.svg'
import iconoMas from '../../../assets/icons/mas.svg'
import iconoPaquete from '../../../assets/icons/paquete.svg'
import iconoUsuario from '../../../assets/icons/usuario.svg'
import { obtenerFidelizacion } from '../../../api/fidelizacion.js'
import Alerta from '../../../components/Alerta.jsx'
import Boton from '../../../components/Boton.jsx'
import Cargando from '../../../components/Cargando.jsx'
import Enlace from '../../../components/Enlace.jsx'
import EstadoVacio from '../../../components/EstadoVacio.jsx'
import LayoutAdmin from '../../../components/layout/LayoutAdmin.jsx'
import Pestanas from '../../../components/Pestanas.jsx'
import TarjetaKpi from '../../../components/TarjetaKpi.jsx'
import { fecha, precio, unidades } from '../../../formato.js'
import { mensajeError } from '../../../i18n/errores.js'
import { RUTAS } from '../../../router.js'
import '../../productor/productor.css'
import '../admin.css'
import { DialogoFinalizar, EstadoCampana, rutaCampana, TEXTOS, textoCondiciones, textoPeriodo, textoRegla } from './comun.jsx'
import './fidelizacion.css'

const FILTROS = ['TODAS', 'ACTIVA', 'PROGRAMADA', 'FINALIZADA']
const enFiltro = (filtro) => (c) => filtro === 'TODAS' || c.estado === filtro || (filtro === 'FINALIZADA' && c.estado === 'CANCELADA')

/**
 * CCPIP-86: la seccion Fidelizacion del sub administrador. Explica la regla base, resume lo que acumularon los
 * compradores, destaca la campana que rige hoy y lista todas, con acceso a sus metricas.
 */
export default function FidelizacionPage({ path }) {
  const { t } = useTranslation()
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [filtro, setFiltro] = useState('TODAS')
  const [aFinalizar, setAFinalizar] = useState(null)
  const [aviso, setAviso] = useState(null)

  const cargar = () => obtenerFidelizacion().then(setDatos).catch(setError)
  useEffect(() => {
    cargar()
  }, [])

  const finalizada = (campana) => {
    setAFinalizar(null)
    setAviso({ tono: 'exito', titulo: t(`${TEXTOS}.${campana.estado === 'CANCELADA' ? 'cancelar' : 'finalizar'}.listo`, { nombre: campana.nombre }) })
    cargar()
  }

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.fidelizacion')} titulo={t(`${TEXTOS}.titulo`)}>
      <div className="fidelizacion__cabecera">
        <p className="body-m">{t(`${TEXTOS}.intro`)}</p>
        <Boton to={RUTAS.adminCampanaNueva} tamano="M" icono={iconoMas}>{t(`${TEXTOS}.nueva`)}</Boton>
      </div>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {error && <Alerta tono="error" titulo={t(`${TEXTOS}.errorCargar`)} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos && (
        <>
          <Totales datos={datos} />
          {datos.campanas.filter((c) => c.estado === 'ACTIVA').map((c) => (
            <CampanaActiva key={c.id} campana={c} hoy={datos.hoy} onFinalizar={() => setAFinalizar(c)} />
          ))}
          <section className="productor__seccion" aria-labelledby="fidelizacion-campanas">
            <h2 id="fidelizacion-campanas" className="heading-h3">{t(`${TEXTOS}.campanas`)}</h2>
            {datos.campanas.length === 0 ? (
              <EstadoVacio titulo={t(`${TEXTOS}.vacio.titulo`)} descripcion={t(`${TEXTOS}.vacio.texto`)}>
                <Boton to={RUTAS.adminCampanaNueva} tamano="M" icono={iconoMas}>{t(`${TEXTOS}.nueva`)}</Boton>
              </EstadoVacio>
            ) : (
              <>
                <Pestanas
                  etiqueta={t(`${TEXTOS}.filtrar`)}
                  valor={filtro}
                  onCambiar={setFiltro}
                  opciones={FILTROS.map((f) => ({ valor: f, etiqueta: t(`${TEXTOS}.filtro.${f}`, { count: datos.campanas.filter(enFiltro(f)).length }) }))}
                />
                <TablaCampanas campanas={datos.campanas.filter(enFiltro(filtro))} onFinalizar={setAFinalizar} />
              </>
            )}
          </section>
        </>
      )}
      <DialogoFinalizar
        campana={aFinalizar}
        onListo={finalizada}
        onError={(descripcion) => {
          setAFinalizar(null)
          setAviso({ tono: 'error', titulo: t(`${TEXTOS}.errorFinalizar`), descripcion })
        }}
        onCerrar={() => setAFinalizar(null)}
      />
    </LayoutAdmin>
  )
}

function Totales({ datos }) {
  const { t } = useTranslation()
  const { puntosOtorgados, puntosDeCampanas, clientes, pedidos } = datos.totales
  const activa = datos.campanas.find((c) => c.estado === 'ACTIVA')
  const proxima = [...datos.campanas].reverse().find((c) => c.estado === 'PROGRAMADA')
  const porcentaje = puntosOtorgados > 0 ? Math.round((puntosDeCampanas / puntosOtorgados) * 100) : 0
  let detalleCampana = t(`${TEXTOS}.kpi.sinCampana`)
  if (activa) detalleCampana = t(`${TEXTOS}.kpi.hasta`, { fecha: fecha(activa.hasta) })
  else if (proxima) detalleCampana = t(`${TEXTOS}.kpi.proxima`, { nombre: proxima.nombre, fecha: fecha(proxima.desde) })
  return (
    <div className="productor__kpis">
      <TarjetaKpi titulo={t(`${TEXTOS}.kpi.puntos`)} valor={unidades(puntosOtorgados)} icono={iconoEstrella} tono="neutro"
        detalle={t(`${TEXTOS}.kpi.deCampanas`, { porcentaje })} />
      <TarjetaKpi titulo={t(`${TEXTOS}.kpi.clientes`)} valor={unidades(clientes)} icono={iconoUsuario} tono="neutro"
        detalle={t(`${TEXTOS}.kpi.clientesDetalle`)} />
      <TarjetaKpi titulo={t(`${TEXTOS}.kpi.pedidos`)} valor={unidades(pedidos)} icono={iconoPaquete} tono="neutro"
        detalle={t(`${TEXTOS}.kpi.pedidosDetalle`)} />
      <TarjetaKpi titulo={t(`${TEXTOS}.kpi.activa`)} valor={activa?.nombre ?? t(`${TEXTOS}.kpi.ninguna`)} icono={iconoCalendario}
        tono={activa ? 'exito' : 'neutro'} detalle={detalleCampana} />
    </div>
  )
}

/** La campana que rige hoy: cuanto lleva, lo que va generando y sus acciones. */
function CampanaActiva({ campana, hoy, onFinalizar }) {
  const { t } = useTranslation()
  const dia = (iso) => new Date(`${iso}T00:00:00Z`).getTime()
  const total = Math.round((dia(campana.hasta) - dia(campana.desde)) / 86400000) + 1
  const transcurridos = Math.min(total, Math.round((dia(hoy) - dia(campana.desde)) / 86400000) + 1)
  const { pedidos, ventas, puntosOtorgados } = campana.resumen
  return (
    <section className="fidelizacion__activa" aria-labelledby={`activa-${campana.id}`}>
      <div className="fidelizacion__activa-cabecera">
        <div>
          <p className="caption fidelizacion__activa-etiqueta">{t(`${TEXTOS}.activaAhora`)}</p>
          <h2 id={`activa-${campana.id}`} className="heading-h3">{campana.nombre}</h2>
          <p className="body-s">{textoRegla(t, campana)} · {textoCondiciones(t, campana)}</p>
        </div>
        <div className="fidelizacion__acciones">
          <Boton tipo="secundario" tamano="M" to={rutaCampana(campana.id)}>{t(`${TEXTOS}.verMetricas`)}</Boton>
          <Boton tipo="terciario" tamano="M" peligro onClick={onFinalizar}>{t(`${TEXTOS}.finalizar.boton`)}</Boton>
        </div>
      </div>
      <div className="fidelizacion__avance">
        <div className="fidelizacion__barra" role="progressbar" aria-valuemin={1} aria-valuemax={total} aria-valuenow={transcurridos}
          aria-label={t(`${TEXTOS}.avance`, { dia: transcurridos, total })}>
          <span style={{ width: `${(transcurridos / total) * 100}%` }} />
        </div>
        <p className="caption">{t(`${TEXTOS}.avance`, { dia: transcurridos, total })} · {textoPeriodo(t, campana)}</p>
      </div>
      <dl className="fidelizacion__cifras">
        <div><dt>{t(`${TEXTOS}.col.pedidos`)}</dt><dd>{unidades(pedidos)}</dd></div>
        <div><dt>{t(`${TEXTOS}.col.ventas`)}</dt><dd>{precio(ventas)}</dd></div>
        <div><dt>{t(`${TEXTOS}.col.puntos`)}</dt><dd>{unidades(puntosOtorgados)}</dd></div>
      </dl>
    </section>
  )
}

function TablaCampanas({ campanas, onFinalizar }) {
  const { t } = useTranslation()
  if (!campanas.length) return <p className="body-s fidelizacion__sin-filtro">{t(`${TEXTOS}.sinFiltro`)}</p>
  return (
    <div className="tabla fidelizacion__tabla" role="table" aria-labelledby="fidelizacion-campanas">
      <div className="tabla__fila tabla__fila--encabezado" role="row">
        <span className="tabla__celda tabla__celda--flex fidelizacion__col-campana" role="columnheader">{t(`${TEXTOS}.col.campana`)}</span>
        <span className="tabla__celda fidelizacion__col-periodo" role="columnheader">{t(`${TEXTOS}.col.periodo`)}</span>
        <span className="tabla__celda fidelizacion__col-estado" role="columnheader">{t(`${TEXTOS}.col.estado`)}</span>
        <span className="tabla__celda admin__num" role="columnheader">{t(`${TEXTOS}.col.pedidos`)}</span>
        <span className="tabla__celda admin__num" role="columnheader">{t(`${TEXTOS}.col.ventas`)}</span>
        <span className="tabla__celda admin__num" role="columnheader">{t(`${TEXTOS}.col.puntos`)}</span>
        <span className="tabla__celda fidelizacion__col-acciones" role="columnheader" aria-label={t(`${TEXTOS}.col.acciones`)} />
      </div>
      {campanas.map((c) => (
        <div key={c.id} className="tabla__fila" role="row">
          <span className="tabla__celda tabla__celda--flex fidelizacion__col-campana" role="cell">
            <Enlace to={rutaCampana(c.id)} className="fidelizacion__nombre">{c.nombre}</Enlace>
            <span className="caption">{textoRegla(t, c)} · {textoCondiciones(t, c)}</span>
          </span>
          <span className="tabla__celda fidelizacion__col-periodo" role="cell">{textoPeriodo(t, c)}</span>
          <span className="tabla__celda fidelizacion__col-estado" role="cell"><EstadoCampana estado={c.estado} /></span>
          <span className="tabla__celda admin__num" role="cell">{unidades(c.resumen.pedidos)}</span>
          <span className="tabla__celda admin__num" role="cell">{precio(c.resumen.ventas)}</span>
          <span className="tabla__celda admin__num" role="cell">{unidades(c.resumen.puntosOtorgados)}</span>
          <span className="tabla__celda fidelizacion__col-acciones" role="cell">
            {c.estado === 'PROGRAMADA' && (
              <Boton tipo="terciario" tamano="S" to={`${rutaCampana(c.id)}/editar`} aria-label={t(`${TEXTOS}.editarDe`, { nombre: c.nombre })}>
                {t(`${TEXTOS}.editar`)}
              </Boton>
            )}
            {(c.estado === 'ACTIVA' || c.estado === 'PROGRAMADA') && (
              <Boton tipo="terciario" tamano="S" peligro onClick={() => onFinalizar(c)}
                aria-label={t(`${TEXTOS}.${c.estado === 'ACTIVA' ? 'finalizar' : 'cancelar'}.de`, { nombre: c.nombre })}>
                {t(`${TEXTOS}.${c.estado === 'ACTIVA' ? 'finalizar' : 'cancelar'}.boton`)}
              </Boton>
            )}
          </span>
        </div>
      ))}
    </div>
  )
}
