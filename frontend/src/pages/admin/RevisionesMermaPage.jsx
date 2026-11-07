import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { aprobarRevisionMerma, listarRevisionesMerma, rechazarRevisionMerma } from '../../api/merma.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { diaMesAnio, unidades } from '../../formato.js'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'

const ESTADOS = ['PENDIENTE', 'APROBADA', 'RECHAZADA']
const TONO = { PENDIENTE: 'aviso', APROBADA: 'exito', RECHAZADA: 'neutro' }

/**
 * CCPIP-120 (RF-62, C8): revisiones de merma registrada por error que piden los puntos de acopio.
 * Decision del cliente del 05/11/2026: el subadministrador las aprueba o las rechaza; al aprobar, las
 * unidades vuelven a su lote con un movimiento nuevo y la merma original no cambia (RNF-16).
 * No hay pantalla en el Figma: sigue la tabla y los dialogos del panel de administracion.
 */
export default function RevisionesMermaPage({ path }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState('PENDIENTE')
  const consultar = useCallback(() => listarRevisionesMerma(estado), [estado])
  const { datos, error, recargar } = useConsulta(consultar)
  const [resolviendo, setResolviendo] = useState(null)
  const [alerta, setAlerta] = useState(null)
  const paginas = usePaginacionLocal(datos ?? [])

  function alResolver(r) {
    setResolviendo(null)
    setAlerta({ tono: 'exito', titulo: t(`admin.revisionesMerma.${r.estado === 'APROBADA' ? 'aprobadaOk' : 'rechazadaOk'}`, { lote: r.lote }) })
    recargar()
  }

  const titulo = t('admin.revisionesMerma.titulo')
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.revisionesMerma')} titulo={titulo}>
      <p className="body-s">{t('admin.revisionesMerma.intro')}</p>
      <Pestanas
        etiqueta={titulo}
        valor={estado}
        onCambiar={(v) => { setEstado(v); setAlerta(null) }}
        opciones={ESTADOS.map((e) => ({ valor: e, etiqueta: t(`admin.revisionesMerma.pestana.${e}`) }))}
      />
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {error && (
        <EstadoVacio titulo={t('admin.revisionesMerma.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}
      {!datos && !error && <Cargando />}
      {datos?.length === 0 && <EstadoVacio titulo={t(`admin.revisionesMerma.vacio.${estado}`)} />}

      {datos?.length > 0 && (
        <div className="tabla tabla--espaciada" role="table" aria-label={titulo}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda revisiones-merma__col-fecha" role="columnheader">{t('admin.revisionesMerma.col.fecha')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.revisionesMerma.col.merma')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.revisionesMerma.col.pedido')}</span>
            <span className="tabla__celda revisiones-merma__col-estado" role="columnheader">{t('admin.revisionesMerma.col.estado')}</span>
          </div>
          {paginas.filas.map((r) => (
            <div key={r.id} className="tabla__fila" role="row">
              <span className="tabla__celda revisiones-merma__col-fecha label-m" role="cell">{diaMesAnio(r.registradaEn)}</span>
              <span className="tabla__celda tabla__celda--flex revisiones-merma__bloque" role="cell">
                <span className="label-m">{`${r.producto} · ${r.lote}`}</span>
                <span className="caption">{[r.punto, r.productor].filter(Boolean).join(' · ')}</span>
                <span className="caption">
                  {t('admin.revisionesMerma.merma', { cantidad: unidades(r.cantidadMerma), causa: r.causa, fecha: diaMesAnio(r.mermaRegistradaEn) })}
                </span>
              </span>
              <span className="tabla__celda tabla__celda--flex revisiones-merma__bloque" role="cell">
                <span className="label-m">{t('admin.revisionesMerma.devolver', { cantidad: unidades(r.cantidad) })}</span>
                <span>{r.motivo}</span>
                <span className="caption">{t('admin.revisionesMerma.pidio', { nombre: r.solicitante })}</span>
              </span>
              <span className="tabla__celda revisiones-merma__col-estado revisiones-merma__bloque" role="cell">
                <Badge tono={TONO[r.estado]}>{t(`admin.revisionesMerma.estado.${r.estado}`)}</Badge>
                {r.estado === 'PENDIENTE' ? (
                  <span className="revisiones-merma__acciones">
                    <Boton tamano="S" onClick={() => setResolviendo({ revision: r, accion: 'aprobar' })}
                      aria-label={t('admin.revisionesMerma.aprobarDe', { lote: r.lote })}>
                      {t('admin.revisionesMerma.aprobar')}
                    </Boton>
                    <Boton tipo="secundario" tamano="S" peligro onClick={() => setResolviendo({ revision: r, accion: 'rechazar' })}
                      aria-label={t('admin.revisionesMerma.rechazarDe', { lote: r.lote })}>
                      {t('admin.revisionesMerma.rechazar')}
                    </Boton>
                  </span>
                ) : (
                  <span className="caption">
                    {r.estado === 'APROBADA' && t('admin.revisionesMerma.reintegradas', { cantidad: unidades(r.cantidadAprobada) })}
                    {r.respuesta && <span className="revisiones-merma__respuesta">{r.respuesta}</span>}
                    <span>{t('admin.revisionesMerma.resolvio', { nombre: r.resueltaPor, fecha: diaMesAnio(r.resueltaEn) })}</span>
                  </span>
                )}
              </span>
            </div>
          ))}
        </div>
      )}
      {datos?.length > 0 && <Paginacion {...paginas} />}

      {resolviendo && (
        <DialogoResolver {...resolviendo} onResuelta={alResolver} onCancelar={() => setResolviendo(null)} />
      )}
    </LayoutAdmin>
  )
}

/** Aprobar (con la cantidad que vuelve al lote, por defecto la pedida) o rechazar (con la respuesta). */
function DialogoResolver({ revision, accion, onResuelta, onCancelar }) {
  const { t } = useTranslation()
  const aprobar = accion === 'aprobar'
  const [cantidad, setCantidad] = useState(String(Number(revision.cantidad)))
  const [respuesta, setRespuesta] = useState('')
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const maximo = Number(revision.cantidad)

  const enviar = (e) => {
    e?.preventDefault?.()
    const faltan = {}
    const n = Number(cantidad)
    if (aprobar && (!cantidad || !(n > 0) || n > maximo)) faltan.cantidad = t('admin.revisionesMerma.validacion.cantidad', { maximo: unidades(maximo) })
    if (!aprobar && !respuesta.trim()) faltan.respuesta = t('admin.revisionesMerma.validacion.respuesta')
    setErrores(faltan)
    if (Object.keys(faltan).length) return
    setEnviando(true)
    setError(null)
    const peticion = aprobar
      ? aprobarRevisionMerma(revision.id, { cantidad, respuesta: respuesta.trim() || null })
      : rechazarRevisionMerma(revision.id, { respuesta: respuesta.trim() })
    peticion.then(onResuelta).catch((err) => { setError(err); setEnviando(false) })
  }

  return (
    <DialogoFormulario
      titulo={t(`admin.revisionesMerma.${accion}Titulo`, { lote: revision.lote })}
      confirmar={t(`admin.revisionesMerma.${accion}`)}
      enviando={enviando}
      onEnviar={enviar}
      onCancelar={onCancelar}
      className="revisiones-merma__dialogo"
      error={error}
      tituloError={t('admin.revisionesMerma.errorResolver')}
    >
      <p className="body-s">{t(`admin.revisionesMerma.${accion}Texto`, { producto: revision.producto, cantidad: unidades(revision.cantidad) })}</p>
      {aprobar && (
        <Campo etiqueta={t('admin.revisionesMerma.cantidad')} type="number" min="1" max={maximo} step="any" inputMode="decimal"
          value={cantidad} error={errores.cantidad} onChange={(e) => setCantidad(e.target.value)} />
      )}
      <Campo etiqueta={t(aprobar ? 'admin.revisionesMerma.respuestaOpcional' : 'admin.revisionesMerma.respuesta')} tipo="area"
        maxLength={500} value={respuesta} error={errores.respuesta} onChange={(e) => setRespuesta(e.target.value)} />
    </DialogoFormulario>
  )
}
