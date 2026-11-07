import { useCallback, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { nombreCausa } from '../../components/DialogoMerma.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LimpiarFiltros from '../../components/LimpiarFiltros.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import { diaMesAnio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './ListaMerma.css'

const SIN_FILTROS = { desde: '', hasta: '', lote: '', producto: '', ubicacion: '', productor: '' }

/**
 * La merma registrada (Figma A-04): manual (CCPIP-76) y automatica por vencimiento (CCPIP-75), la
 * mas reciente primero. `consultar(pagina)` llama al API del rol; `conUbicacion` muestra donde
 * estaba el lote (el productor tiene lotes en su almacen y en los puntos). `ir` ({ ruta, texto }) lleva a
 * donde se marca la merma, con `irTexto` que lo explica. `onDatos` recibe cada pagina.
 * CCPIP-120: con `revision` ({ listar, solicitar }) el punto ve el estado de la revision de cada merma
 * manual y la pide si se registro por error.
 */
export default function ListaMerma({ consultar, exportar, conUbicacion = false, conProductor = false, ir, irTexto, onDatos, revision }) {
  const { t, i18n } = useTranslation()
  const [pagina, setPagina] = useState(0)
  const [escritos, setEscritos] = useState(SIN_FILTROS)
  const [filtros, setFiltros] = useState(escritos)
  const [filtrosAplicados, setFiltrosAplicados] = useState(false)
  const cargar = useCallback(() => consultar(pagina, filtros).then((d) => { onDatos?.(d); return d }), [consultar, pagina, filtros, onDatos])
  const { datos, error, recargar } = useConsulta(cargar)
  const { revisiones, pidiendo, setPidiendo, alPedir, aviso, setAviso } = useRevisiones(revision)
  const titulo = t('merma.lista.titulo')
  const cambiar = (campo) => (e) => setEscritos((actual) => ({ ...actual, [campo]: e.target.value }))
  const aplicar = () => { setPagina(0); setFiltros(escritos); setFiltrosAplicados(true) }
  // CCPIP-417: vuelve a toda la merma, sin filtros
  const limpiar = () => { setPagina(0); setEscritos(SIN_FILTROS); setFiltros(SIN_FILTROS); setFiltrosAplicados(false) }

  return (
    <>
      <div className="merma__barra">
        <p className="body-s">{irTexto}</p>
        {/* Las descargas, PDF y Excel juntos, arriba; asi los filtros y "Aplicar" caben en una sola fila */}
        <span className="merma__exportar">
          {exportar && ['pdf', 'xlsx'].map((formato) => (
            <a key={formato} className="boton boton--secundario boton--M" href={exportar(formato, filtros)} download>
              {t(`merma.lista.filtros.exportar.${formato}`)}
            </a>
          ))}
          {ir && <Boton tipo="secundario" tamano="M" to={ir.ruta}>{ir.texto}</Boton>}
        </span>
      </div>
      <section className="merma__filtros" aria-label={t('merma.lista.filtros.titulo')}>
        <Campo etiqueta={t('merma.lista.filtros.desde')} type="date" value={escritos.desde} max={escritos.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('merma.lista.filtros.hasta')} type="date" value={escritos.hasta} min={escritos.desde || undefined} onChange={cambiar('hasta')} />
        <Campo etiqueta={t('merma.lista.filtros.lote')} value={escritos.lote} onChange={cambiar('lote')} />
        <Campo etiqueta={t('merma.lista.filtros.producto')} value={escritos.producto} onChange={cambiar('producto')} />
        <Campo etiqueta={t('merma.lista.filtros.ubicacion')} value={escritos.ubicacion} onChange={cambiar('ubicacion')} />
        {/* CCPIP-417: el punto filtra tambien por productor; el productor solo ve su propia merma */}
        {conProductor && <Campo etiqueta={t('merma.lista.filtros.productor')} value={escritos.productor} onChange={cambiar('productor')} />}
        <Boton tamano="M" onClick={aplicar}>{t('merma.lista.filtros.aplicar')}</Boton>
        <LimpiarFiltros activos={filtrosAplicados || Object.values(escritos).some(Boolean)} onLimpiar={limpiar} />
      </section>

      {aviso && <Alerta tono="exito" titulo={aviso} onCerrar={() => setAviso(null)} />}
      {error && (
        <EstadoVacio titulo={t('merma.lista.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}
      {!datos && !error && <Cargando />}
      {!error && datos?.items.length === 0 && <EstadoVacio titulo={t('merma.lista.vacio')} descripcion={t(filtrosAplicados ? 'merma.lista.filtros.vacio' : 'merma.lista.vacioTexto')} />}

      {!error && datos?.items.length > 0 && (
        <>
          <div className="tabla tabla--espaciada" role="table" aria-label={titulo}>
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda merma__col-fecha" role="columnheader">{t('merma.lista.col.fecha')}</span>
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('merma.lista.col.producto')}</span>
              {conProductor && <span className="tabla__celda merma__col-productor" role="columnheader">{t('merma.lista.col.productor')}</span>}
              {conUbicacion && <span className="tabla__celda merma__col-ubicacion" role="columnheader">{t('merma.lista.col.ubicacion')}</span>}
              <span className="tabla__celda merma__col-causa" role="columnheader">{t('merma.lista.col.causa')}</span>
              <span className="tabla__celda merma__col-cantidad" role="columnheader">{t('merma.lista.col.cantidad')}</span>
              <span className="tabla__celda merma__col-registro" role="columnheader">{t('merma.lista.col.registro')}</span>
              <span className="tabla__celda merma__col-estado" role="columnheader">{t('merma.lista.col.estado')}</span>
            </div>
            {datos.items.map((m) => (
              <div key={m.id} className="tabla__fila" role="row">
                <span className="tabla__celda merma__col-fecha label-m" role="cell">{diaMesAnio(m.registradoEn)}</span>
                <span className="tabla__celda tabla__celda--flex" role="cell">{`${m.producto} · ${m.lote}`}</span>
                {conProductor && <span className="tabla__celda merma__col-productor" role="cell">{m.productor ?? '—'}</span>}
                {conUbicacion && <span className="tabla__celda merma__col-ubicacion" role="cell">{m.ubicacion}</span>}
                <span className="tabla__celda merma__col-causa" role="cell">
                  {m.automatica ? t('merma.lista.automaticaCausa', { causa: nombreCausa(m, i18n) }) : nombreCausa(m, i18n)}
                  {m.detalle && <span className="caption merma__detalle">{m.detalle}</span>}
                </span>
                <span className="tabla__celda merma__col-cantidad" role="cell">{`−${unidades(m.cantidad)}`}</span>
                <span className="tabla__celda merma__col-registro caption" role="cell">
                  {m.automatica ? t('merma.lista.sistema') : (m.registradoPor ?? (conUbicacion ? t('merma.lista.elPunto') : '—'))}
                </span>
                <span className="tabla__celda merma__col-estado" role="cell">
                  <Badge tono={m.automatica ? 'info' : 'error'}>{t(m.automatica ? 'merma.lista.automatica' : 'merma.lista.confirmada')}</Badge>
                  {revision && !m.automatica && <EstadoRevision merma={m} revision={revisiones[m.id]} onPedir={() => setPidiendo(m)} />}
                </span>
              </div>
            ))}
          </div>
          <div className="merma__pie">
            <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} total={datos.total} onCambiar={setPagina} />
          </div>
        </>
      )}
      {pidiendo && <DialogoRevision merma={pidiendo} solicitar={revision.solicitar} onPedida={alPedir} onCancelar={() => setPidiendo(null)} />}
    </>
  )
}

/** CCPIP-120: la revision mas reciente de cada merma, por id de merma. Sin `revision` no consulta nada. */
function useRevisiones(revision) {
  const { t } = useTranslation()
  const listar = useCallback(() => (revision ? revision.listar() : Promise.resolve([])), [revision])
  const { datos, setDatos } = useConsulta(listar)
  const [pidiendo, setPidiendo] = useState(null)
  const [aviso, setAviso] = useState(null)
  // La lista llega de la mas reciente a la mas antigua: la primera de cada merma es la vigente
  const revisiones = useMemo(() => {
    const porMerma = {}
    for (const r of datos ?? []) if (!porMerma[r.mermaId]) porMerma[r.mermaId] = r
    return porMerma
  }, [datos])
  const alPedir = (r) => {
    setPidiendo(null)
    setDatos((lista) => [r, ...(lista ?? [])])
    setAviso(t('merma.revision.pedida', { lote: r.lote }))
  }
  return { revisiones, pidiendo, setPidiendo, alPedir, aviso, setAviso }
}

function EstadoRevision({ merma, revision, onPedir }) {
  const { t } = useTranslation()
  if (revision?.estado === 'PENDIENTE') return <Badge tono="aviso">{t('merma.revision.pendiente')}</Badge>
  if (revision?.estado === 'APROBADA') {
    return <Badge tono="exito">{t('merma.revision.aprobada', { cantidad: unidades(revision.cantidadAprobada) })}</Badge>
  }
  return (
    <span className="merma__revision">
      {revision?.estado === 'RECHAZADA' && (
        <span className="caption">{t('merma.revision.rechazada', { respuesta: revision.respuesta })}</span>
      )}
      <Boton tipo="terciario" tamano="S" onClick={onPedir} aria-label={t('merma.revision.pedirDe', { lote: merma.lote })}>
        {t('merma.revision.pedir')}
      </Boton>
    </span>
  )
}

/** Pedir la revision: cuantas unidades no debieron darse de baja y por que. */
function DialogoRevision({ merma, solicitar, onPedida, onCancelar }) {
  const { t } = useTranslation()
  const [cantidad, setCantidad] = useState('')
  const [motivo, setMotivo] = useState('')
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const maximo = Number(merma.cantidad)

  const enviar = (e) => {
    e?.preventDefault?.()
    const n = Number(cantidad)
    const faltan = {}
    if (!cantidad || !(n > 0) || n > maximo) faltan.cantidad = t('merma.revision.validacion.cantidad', { maximo: unidades(maximo) })
    if (motivo.trim().length < 10) faltan.motivo = t('merma.revision.validacion.motivo')
    setErrores(faltan)
    if (Object.keys(faltan).length) return
    setEnviando(true)
    setError(null)
    solicitar(merma.id, { cantidad, motivo: motivo.trim() })
      .then(onPedida)
      .catch((err) => { setError(err); setEnviando(false) })
  }

  return (
    <DialogoFormulario
      titulo={t('merma.revision.titulo', { lote: merma.lote })}
      confirmar={t('merma.revision.enviar')}
      enviando={enviando}
      onEnviar={enviar}
      onCancelar={onCancelar}
      className="merma__dialogo-revision"
      error={error}
      tituloError={t('merma.revision.error')}
    >
      <p className="body-s">{t('merma.revision.intro', { producto: merma.producto, cantidad: unidades(merma.cantidad) })}</p>
      <Campo etiqueta={t('merma.revision.cantidad')} type="number" min="1" max={maximo} step="any" inputMode="decimal"
        value={cantidad} error={errores.cantidad} onChange={(e) => setCantidad(e.target.value)} />
      <Campo etiqueta={t('merma.revision.motivo')} tipo="area" maxLength={500} placeholder={t('merma.revision.motivoEjemplo')}
        value={motivo} error={errores.motivo} onChange={(e) => setMotivo(e.target.value)} />
      <p className="caption">{t('merma.revision.aviso')}</p>
    </DialogoFormulario>
  )
}
