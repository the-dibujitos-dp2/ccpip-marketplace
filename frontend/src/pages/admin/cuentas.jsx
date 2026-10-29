// Piezas compartidas de las pantallas de cuentas (SA-03, SA-04): estado, formulario y errores.
import { useTranslation } from 'react-i18next'
import Badge from '../../components/Badge.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import { mensajeError } from '../../i18n/errores.js'

const TONO_ESTADO = { ACTIVA: 'exito', SUSPENDIDA: 'aviso', DESACTIVADA: 'neutro' }
const TELEFONO = /^\+?[0-9 ]{6,19}$/
const CORREO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
// Codes del servidor que senalan un campo concreto del formulario
const CAMPO_DEL_CODE = { CORREO_EN_USO: 'correo', PUNTOS_REQUERIDOS: 'puntos', PUNTO_INVALIDO: 'puntos', PUNTOS_NO_APLICAN: 'puntos' }

/** Roles que crea el superadministrador (CCPIP-301, 304). */
export const ROLES_ADMINISTRATIVOS = ['SUB_ADMIN', 'ADMIN_PUNTO']

/** Estado de la cuenta, y si la persona aun no eligio su contrasena. Siempre texto y color. */
export function EstadoCuenta({ cuenta }) {
  const { t } = useTranslation()
  return (
    <span className="cuentas__estado">
      <Badge tono={TONO_ESTADO[cuenta.estado]}>{t(`cuentas.estado.${cuenta.estado}`)}</Badge>
      {cuenta.accesoPendiente && cuenta.estado === 'ACTIVA' && <Badge tono="info" conPunto={false}>{t('cuentas.accesoPendiente')}</Badge>}
    </span>
  )
}

/** Validacion en la pantalla; el servidor vuelve a validar todo. `nueva`: tambien correo y rol. */
export function validarCuenta(f, nueva, t) {
  const e = {}
  if (!f.nombre.trim()) e.nombre = t('cuentas.validacion.nombre')
  if (nueva && !CORREO.test(f.correo.trim())) e.correo = t('cuentas.validacion.correo')
  if (f.telefono.trim() && !TELEFONO.test(f.telefono.trim())) e.telefono = t('cuentas.validacion.telefono')
  if (nueva && !f.rol) e.rol = t('cuentas.validacion.rol')
  if (f.rol === 'ADMIN_PUNTO' && f.puntos.length === 0) e.puntos = t('cuentas.validacion.puntos')
  return e
}

/** Lo que se envia: telefono vacio como null y puntos solo para el administrador de punto. */
export function cuerpoCuenta(f, nueva) {
  const datos = {
    nombre: f.nombre.trim(),
    telefono: f.telefono.trim() || null,
    puntos: f.rol === 'ADMIN_PUNTO' ? f.puntos : [],
  }
  return nueva ? { ...datos, correo: f.correo.trim(), rol: f.rol } : datos
}

/**
 * Errores del servidor en el formulario: por campo si se puede ({ campos } o un code conocido) y,
 * si no, un mensaje general. Devuelve { campos, general }.
 */
export function erroresDelServidor(error, t) {
  const campos = {}
  Object.keys(error.body?.campos ?? {}).forEach((campo) => {
    campos[campo] = t(`cuentas.validacion.${campo}`, { defaultValue: mensajeError(error) })
  })
  const porCode = CAMPO_DEL_CODE[error.code]
  if (porCode) campos[porCode] = mensajeError(error)
  return { campos, general: Object.keys(campos).length ? null : mensajeError(error) }
}

/**
 * Datos autorizados de una cuenta administrativa. En el alta (`nueva`) tambien correo y rol; al
 * modificar, el correo y el rol no se editan (CCPIP-302, 305). Los puntos solo para ADMIN_PUNTO.
 */
export function FormularioCuenta({ f, set, setPuntos, errores, nueva, puntos }) {
  const { t } = useTranslation()
  const marcar = (id) => (e) => setPuntos(e.target.checked ? [...f.puntos, id] : f.puntos.filter((p) => p !== id))
  return (
    <>
      <div className="cuentas__campos">
        <Campo etiqueta={t('cuentas.campo.nombre')} value={f.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={160} autoComplete="off" />
        {nueva && (
          <Campo etiqueta={t('cuentas.campo.correo')} type="email" value={f.correo} onChange={set('correo')} error={errores.correo}
            maxLength={255} autoComplete="off" ayuda={t('cuentas.ayudaCorreo')} />
        )}
        <Campo etiqueta={t('cuentas.campo.telefono')} value={f.telefono} onChange={set('telefono')} error={errores.telefono}
          inputMode="tel" maxLength={20} autoComplete="off" ayuda={t('cuentas.opcional')} />
        {nueva && (
          <Campo etiqueta={t('cuentas.campo.rol')} tipo="selector" value={f.rol} onChange={set('rol')} error={errores.rol}
            opciones={ROLES_ADMINISTRATIVOS.map((r) => ({ valor: r, etiqueta: t(`roles.${r}`) }))} />
        )}
      </div>
      {f.rol === 'ADMIN_PUNTO' && (
        <fieldset className="cuentas__puntos" aria-describedby={errores.puntos ? 'cuentas-puntos-error' : undefined}>
          <legend className="campo__etiqueta">{t('cuentas.campo.puntos')}</legend>
          <p className="caption">{t('cuentas.ayudaPuntos')}</p>
          {puntos === null && <Cargando texto={t('cuentas.cargandoPuntos')} />}
          {(puntos ?? []).map((p) => (
            <label key={p.id} className="cuentas__punto">
              <input type="checkbox" checked={f.puntos.includes(p.id)} onChange={marcar(p.id)} />
              <span>{p.nombre}</span>
              <span className="caption">{p.departamento}</span>
            </label>
          ))}
          {errores.puntos && <p id="cuentas-puntos-error" className="cuentas__error" role="alert">{errores.puntos}</p>}
        </fieldset>
      )}
    </>
  )
}
