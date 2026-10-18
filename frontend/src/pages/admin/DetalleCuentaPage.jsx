import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEditar from '../../assets/icons/editar.svg'
import iconoPausa from '../../assets/icons/pausa.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoSobre from '../../assets/icons/sobre.svg'
import { cambiarEstadoCuenta, modificarCuenta, obtenerCuenta, puntosAsignables, reenviarAcceso } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaCorta, fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import { cuerpoCuenta, erroresDelServidor, EstadoCuenta, FormularioCuenta, validarCuenta } from './cuentas.jsx'
import '../productor/productor.css'
import './admin.css'

const TONO_DIALOGO = { suspender: 'destructivo', desactivar: 'destructivo', reactivar: 'primario' }

const formulario = (c) => ({ nombre: c.nombre, correo: c.correo, telefono: c.telefono ?? '', rol: c.rol, puntos: c.puntos.map((p) => p.id) })

/** Aviso de exito de una accion que manda correo, o advertencia si el correo no salio. */
function avisoDe(t, clave, avisoEnviado) {
  return avisoEnviado
    ? { tono: 'exito', titulo: t(`cuentas.hecho.${clave}`) }
    : { tono: 'aviso', titulo: t(`cuentas.hecho.${clave}`), descripcion: t('cuentas.sinCorreo') }
}

/**
 * SA-03 y SA-04: detalle de una cuenta con su historial. El superadministrador modifica los datos
 * autorizados (CCPIP-302, 305), desactiva (CCPIP-303, 306) y reenvia el acceso; la administracion
 * autorizada suspende y reactiva con motivo (CCPIP-307, 308). Los botones siguen `acciones`, que manda
 * el servidor; este vuelve a validar cada accion.
 */
export default function DetalleCuentaPage({ path, id, search = '' }) {
  const { t } = useTranslation()
  const [cuenta, setCuenta] = useState(null)
  const [error, setError] = useState(null)
  const [alerta, setAlerta] = useState(() => {
    const creada = new URLSearchParams(search).get('creada')
    return creada ? avisoDe(t, 'crear', creada === 'enviado') : null
  })
  const [editando, setEditando] = useState(false)
  const [f, setF] = useState(null)
  const [errores, setErrores] = useState({})
  const [puntos, setPuntos] = useState(null)
  const [guardando, setGuardando] = useState(false)
  const [dialogo, setDialogo] = useState(null) // suspender | reactivar | desactivar
  const [motivo, setMotivo] = useState('')
  const [errorMotivo, setErrorMotivo] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  const cargar = useCallback(async () => {
    try {
      setCuenta(await obtenerCuenta(id))
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [id])

  useEffect(() => {
    cargar()
  }, [cargar])

  async function editar() {
    setF(formulario(cuenta))
    setErrores({})
    setEditando(true)
    if (cuenta.rol === 'ADMIN_PUNTO' && !puntos) {
      try {
        setPuntos(await puntosAsignables())
      } catch {
        setPuntos([])
      }
    }
  }

  const set = (campo) => (e) => {
    setF((actual) => ({ ...actual, [campo]: e.target.value }))
    setErrores((actual) => ({ ...actual, [campo]: undefined }))
  }
  const elegirPuntos = (lista) => {
    setF((actual) => ({ ...actual, puntos: lista }))
    setErrores((actual) => ({ ...actual, puntos: undefined }))
  }

  async function guardar(evento) {
    evento.preventDefault()
    setAlerta(null)
    const e = validarCuenta(f, false, t)
    setErrores(e)
    if (Object.keys(e).length) return
    setGuardando(true)
    try {
      setCuenta(await modificarCuenta(id, cuerpoCuenta(f, false)))
      setEditando(false)
      setAlerta({ tono: 'exito', titulo: t('cuentas.hecho.modificar') })
    } catch (error) {
      const { campos, general } = erroresDelServidor(error, t)
      setErrores(campos)
      if (general) setAlerta({ tono: 'error', titulo: t('cuentas.errorGuardar'), descripcion: general })
    } finally {
      setGuardando(false)
    }
  }

  function abrir(accion) {
    setMotivo('')
    setErrorMotivo(null)
    setDialogo(accion)
  }

  async function confirmar() {
    if (!motivo.trim()) {
      setErrorMotivo(t('cuentas.validacion.motivo'))
      return
    }
    setOcupado(true)
    setAlerta(null)
    try {
      const r = await cambiarEstadoCuenta(id, dialogo, motivo.trim())
      setCuenta(r.cuenta)
      setEditando(false)
      setAlerta(avisoDe(t, dialogo, r.avisoEnviado))
      setDialogo(null)
    } catch (e) {
      setErrorMotivo(mensajeError(e))
    } finally {
      setOcupado(false)
    }
  }

  async function reenviar() {
    setAlerta(null)
    try {
      const r = await reenviarAcceso(id)
      setCuenta(r.cuenta)
      setAlerta(avisoDe(t, 'reenviar', r.avisoEnviado))
    } catch (e) {
      setAlerta({ tono: 'error', titulo: t('cuentas.errorReenviar'), descripcion: mensajeError(e) })
    }
  }

  const titulo = cuenta?.nombre ?? t('cuentas.detalle')
  return (
    <LayoutAdmin path={path} migas={`${t('admin.nav.cuentas')} / ${titulo}`} titulo={titulo}>
      {error && <Alerta tono="error" titulo={mensajeError(error, 'cuentas.errorCargar')} />}
      {!cuenta && !error && <p className="caption">{t('comun.cargando')}</p>}
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {cuenta && (
        <div className="cuenta-admin">
          <div className="cuenta-admin__principal">
            <section className="tarjeta-panel" aria-labelledby="cuenta-datos">
              <div className="cuenta-admin__cabecera">
                <div>
                  <h2 id="cuenta-datos" className="heading-h3">{t('cuentas.datos')}</h2>
                  <EstadoCuenta cuenta={cuenta} />
                </div>
                {cuenta.acciones.editar && !editando && (
                  <Boton tipo="secundario" tamano="S" icono={iconoEditar} onClick={editar}>{t('cuentas.editar')}</Boton>
                )}
              </div>
              {editando ? (
                <form className="cuentas__formulario" onSubmit={guardar} noValidate aria-label={t('cuentas.editarDatos')}>
                  <FormularioCuenta f={f} set={set} setPuntos={elegirPuntos} errores={errores} puntos={puntos} />
                  <p className="caption">{t('cuentas.noEditables')}</p>
                  <div className="cuentas__acciones">
                    <Boton tipo="terciario" tamano="M" onClick={() => setEditando(false)} disabled={guardando}>{t('comun.cancelar')}</Boton>
                    <Boton type="submit" tamano="M" disabled={guardando}>{t('cuentas.guardar')}</Boton>
                  </div>
                </form>
              ) : (
                <Datos cuenta={cuenta} />
              )}
            </section>
            <Acciones cuenta={cuenta} onAccion={abrir} onReenviar={reenviar} />
          </div>
          <Historial historial={cuenta.historial} />
        </div>
      )}
      <Dialogo
        abierto={dialogo != null}
        titulo={dialogo ? t(`cuentas.dialogo.${dialogo}.titulo`, { nombre: cuenta?.nombre }) : ''}
        confirmar={dialogo ? t(`cuentas.dialogo.${dialogo}.confirmar`) : undefined}
        tipoConfirmar={TONO_DIALOGO[dialogo]}
        onConfirmar={confirmar}
        onCancelar={() => setDialogo(null)}
        ocupado={ocupado}
      >
        {dialogo && (
          <>
            <p>{t(`cuentas.dialogo.${dialogo}.texto`)}</p>
            <Campo
              etiqueta={t('cuentas.campo.motivo')}
              tipo="area"
              rows={3}
              maxLength={300}
              value={motivo}
              onChange={(e) => {
                setMotivo(e.target.value)
                setErrorMotivo(null)
              }}
              error={errorMotivo}
              ayuda={t('cuentas.ayudaMotivo')}
            />
          </>
        )}
      </Dialogo>
    </LayoutAdmin>
  )
}

function Datos({ cuenta }) {
  const { t } = useTranslation()
  const filas = [
    ['correo', cuenta.correo],
    ['rol', t(`roles.${cuenta.rol}`, { defaultValue: cuenta.rolNombre })],
    ['telefono', cuenta.telefono],
    ['creada', fechaCorta(cuenta.creadaEn.slice(0, 10))],
  ]
  if (cuenta.rol === 'ADMIN_PUNTO') filas.push(['puntos', cuenta.puntos.map((p) => p.nombre).join(', ')])
  return (
    <dl className="cuenta-admin__datos">
      {filas.filter(([, valor]) => valor).map(([clave, valor]) => (
        <div key={clave}>
          <dt className="caption">{t(`cuentas.campo.${clave}`)}</dt>
          <dd>{valor}</dd>
        </div>
      ))}
    </dl>
  )
}

function Acciones({ cuenta, onAccion, onReenviar }) {
  const { t } = useTranslation()
  const a = cuenta.acciones
  if (!a.suspender && !a.reactivar && !a.desactivar && !a.reenviarAcceso) {
    return cuenta.estado === 'DESACTIVADA' ? <p className="caption">{t('cuentas.desactivadaNota')}</p> : null
  }
  return (
    <section className="tarjeta-panel" aria-labelledby="cuenta-acciones">
      <h2 id="cuenta-acciones" className="heading-h4">{t('cuentas.accionesTitulo')}</h2>
      <div className="productor__acciones-barra">
        {a.reenviarAcceso && <Boton tipo="secundario" tamano="M" icono={iconoSobre} onClick={onReenviar}>{t('cuentas.reenviar')}</Boton>}
        {a.reactivar && <Boton tamano="M" onClick={() => onAccion('reactivar')}>{t('cuentas.reactivar')}</Boton>}
        {a.suspender && <Boton tipo="secundario" tamano="M" icono={iconoPausa} onClick={() => onAccion('suspender')}>{t('cuentas.suspender')}</Boton>}
        {a.desactivar && <Boton tipo="destructivo" tamano="M" icono={iconoProhibido} onClick={() => onAccion('desactivar')}>{t('cuentas.desactivar')}</Boton>}
      </div>
      <p className="caption">{t('cuentas.accionesNota')}</p>
    </section>
  )
}

/** Un valor del historial: listas unidas, estados traducidos y vacio como raya. */
function valor(t, clave, v) {
  if (v == null || v === '') return '—'
  if (Array.isArray(v)) return v.length ? v.join(', ') : '—'
  if (clave === 'estado') return t(`cuentas.estado.${v}`, { defaultValue: v })
  if (clave === 'rol') return t(`roles.${v}`, { defaultValue: v })
  return String(v)
}

function Historial({ historial }) {
  const { t } = useTranslation()
  return (
    <aside className="cuenta-admin__lateral" aria-labelledby="cuenta-historial">
      <h2 id="cuenta-historial" className="heading-h4">{t('cuentas.historial')}</h2>
      {historial.length === 0 ? (
        <p className="caption">{t('cuentas.sinHistorial')}</p>
      ) : (
        <ol className="cuenta-admin__historial" aria-labelledby="cuenta-historial">
          {historial.map((h) => (
            <li key={h.id}>
              <span className="label-m">{t(`cuentas.accion.${h.accion}`)}</span>
              <span className="caption">{t('cuentas.porQuien', { fecha: fechaHora(h.realizadoEn), nombre: h.realizadoPor })}</span>
              {h.motivo && <span className="body-s">{t('cuentas.motivo', { motivo: h.motivo })}</span>}
              {Object.entries(h.cambios ?? {}).map(([clave, cambio]) => (
                <span key={clave} className="caption">
                  {cambio && typeof cambio === 'object' && !Array.isArray(cambio)
                    ? `${t(`cuentas.campo.${clave}`)}: ${valor(t, clave, cambio.antes)} → ${valor(t, clave, cambio.despues)}`
                    : `${t(`cuentas.campo.${clave}`)}: ${valor(t, clave, cambio)}`}
                </span>
              ))}
            </li>
          ))}
        </ol>
      )}
    </aside>
  )
}
