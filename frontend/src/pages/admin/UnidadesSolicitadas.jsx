import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { solicitudesUnidad } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import { fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'

const ESTADOS = ['PENDIENTE', 'APROBADA', 'RECHAZADA']

/**
 * Catalogo maestro › Unidades solicitadas. CCPIP-137: el superadministrador aprueba una unidad que pidio un
 * productor (CCPIP-119) y entra al catalogo cerrado, con el nombre y codigo que la Camara decida. CCPIP-353:
 * la rechaza indicando el motivo, que ve el productor. Una solicitud se decide una sola vez.
 */
export default function UnidadesSolicitadas() {
  const { t } = useTranslation()
  const [estado, setEstado] = useState('PENDIENTE')
  const consultar = useCallback(() => solicitudesUnidad.listar(estado), [estado])
  const { datos: solicitudes, error, recargar } = useConsulta(consultar)
  const [decision, setDecision] = useState(null)
  const [form, setForm] = useState({})
  const [errores, setErrores] = useState({})
  const [errorEnvio, setErrorEnvio] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [alerta, setAlerta] = useState(null)

  function abrir(solicitud, accion) {
    setDecision({ solicitud, accion })
    setForm({ nombre: solicitud.nombre, nombreEn: '', codigo: '', motivo: '' })
    setErrores({})
    setErrorEnvio(null)
  }

  async function enviar(e) {
    e?.preventDefault()
    if (!form.motivo.trim()) {
      setErrores({ motivo: t('unidadesSolicitadas.errorMotivo') })
      return
    }
    setEnviando(true)
    setErrorEnvio(null)
    const { solicitud, accion } = decision
    try {
      if (accion === 'aprobar') {
        const r = await solicitudesUnidad.aprobar(solicitud.id, {
          nombre: form.nombre.trim() || null, nombreEn: form.nombreEn.trim() || null, codigo: form.codigo.trim() || null, motivo: form.motivo.trim(),
        })
        setAlerta({ tono: 'exito', titulo: t('unidadesSolicitadas.aprobada', { nombre: r.unidad.nombre, codigo: r.unidad.codigo }) })
      } else {
        await solicitudesUnidad.rechazar(solicitud.id, form.motivo.trim())
        setAlerta({ tono: 'exito', titulo: t('unidadesSolicitadas.rechazada', { nombre: solicitud.nombre }) })
      }
      setDecision(null)
      recargar()
    } catch (err) {
      setErrorEnvio(err)
    } finally {
      setEnviando(false)
    }
  }

  const set = (campo) => (e) => setForm({ ...form, [campo]: e.target.value })
  const pendientes = estado === 'PENDIENTE'

  return (
    <section className="catalogo-maestro__unidades" aria-labelledby="titulo-unidades-solicitadas">
      <h2 id="titulo-unidades-solicitadas" className="heading-h4">{t('unidadesSolicitadas.titulo')}</h2>
      <p className="body-s">{t('unidadesSolicitadas.texto')}</p>
      <div className="catalogo-maestro__barra">
        <Campo etiqueta={t('unidadesSolicitadas.filtro')} tipo="selector" value={estado} onChange={(e) => setEstado(e.target.value)}
          opciones={ESTADOS.map((e) => ({ valor: e, etiqueta: t(`unidadesSolicitadas.estado.${e}`) }))} />
      </div>
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {error && <Alerta tono="error" titulo={t('unidadesSolicitadas.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {solicitudes?.length === 0 && <EstadoVacio titulo={t(`unidadesSolicitadas.vacio.${estado}`)} />}
      {solicitudes?.length > 0 && (
        <ul className="catalogo-maestro__solicitudes" aria-label={t(`unidadesSolicitadas.estado.${estado}`)}>
          {solicitudes.map((s) => (
            <li key={s.id} className="tarjeta-panel catalogo-maestro__solicitud">
              <p className="label-m">{s.nombre}</p>
              <p className="caption">{t('unidadesSolicitadas.de', { productor: s.productor, fecha: fechaHora(s.creadaEn) })}</p>
              <p className="body-s">“{s.justificacion}”</p>
              {!pendientes && (
                <p className="caption">
                  {s.estado === 'APROBADA'
                    ? t('unidadesSolicitadas.entro', { nombre: s.unidad?.nombre, codigo: s.unidad?.codigo })
                    : t('unidadesSolicitadas.motivo', { motivo: s.motivo })}
                  {' · '}
                  {t('unidadesSolicitadas.decidida', { nombre: s.decididaPor, fecha: fechaHora(s.decididaEn) })}
                </p>
              )}
              {pendientes && (
                <div className="catalogo-maestro__acciones-solicitud">
                  <Boton tamano="S" onClick={() => abrir(s, 'aprobar')} aria-label={t('unidadesSolicitadas.aprobarDe', { nombre: s.nombre })}>
                    {t('unidadesSolicitadas.aprobar')}
                  </Boton>
                  <Boton tipo="secundario" tamano="S" onClick={() => abrir(s, 'rechazar')} aria-label={t('unidadesSolicitadas.rechazarDe', { nombre: s.nombre })}>
                    {t('unidadesSolicitadas.rechazar')}
                  </Boton>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {decision && (
        <DialogoFormulario
          titulo={t(`unidadesSolicitadas.dialogo.${decision.accion}`, { nombre: decision.solicitud.nombre })}
          confirmar={t(`unidadesSolicitadas.${decision.accion}`)}
          enviando={enviando}
          onEnviar={enviar}
          onCancelar={() => setDecision(null)}
          className="catalogo-maestro__form"
          error={errorEnvio}
          tituloError={t('unidadesSolicitadas.errorDecidir')}
        >
          {decision.accion === 'aprobar' && (
            <>
              <p className="body-s">{t('unidadesSolicitadas.textoAprobar')}</p>
              <Campo etiqueta={t('unidadesSolicitadas.nombre')} value={form.nombre} onChange={set('nombre')} maxLength={60} />
              <Campo etiqueta={t('unidadesSolicitadas.nombreEn')} value={form.nombreEn} onChange={set('nombreEn')} maxLength={60} lang="en"
                ayuda={t('unidadesSolicitadas.nombreEnAyuda')} />
              <Campo etiqueta={t('unidadesSolicitadas.codigo')} value={form.codigo} onChange={set('codigo')} maxLength={20} autoComplete="off"
                ayuda={t('unidadesSolicitadas.codigoAyuda')} />
            </>
          )}
          {decision.accion === 'rechazar' && <p className="body-s">{t('unidadesSolicitadas.textoRechazar')}</p>}
          <Campo etiqueta={t('unidadesSolicitadas.motivoCampo')} tipo="area" value={form.motivo} onChange={set('motivo')} error={errores.motivo} maxLength={500} />
        </DialogoFormulario>
      )}
    </section>
  )
}
