import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import { registrar } from '../../api/auth.js'
import { ApiError } from '../../api/http.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { CELULAR_VALIDO, contrasenaValida, CORREO_VALIDO } from './acceso.js'

export const REGISTRO_VACIO = { nombres: '', apellidos: '', correo: '', celular: '', contrasena: '', aceptaTerminos: false }

// Campos del servidor (DATOS_INVALIDOS) -> campos del formulario
const CAMPOS_SERVIDOR = {
  firstName: 'nombres',
  lastName: 'apellidos',
  email: 'correo',
  phone: 'celular',
  password: 'contrasena',
  acceptTerms: 'aceptaTerminos',
}

function validar(f, t) {
  const errores = {}
  if (!f.nombres.trim()) errores.nombres = t('registro.validacion.nombres')
  if (!f.apellidos.trim()) errores.apellidos = t('registro.validacion.apellidos')
  if (!CORREO_VALIDO.test(f.correo.trim())) errores.correo = t('ingresar.validacion.correo')
  if (f.celular.trim() && !CELULAR_VALIDO.test(f.celular.trim())) errores.celular = t('registro.validacion.celular')
  if (!contrasenaValida(f.contrasena)) errores.contrasena = t('registro.validacion.contrasena')
  if (!f.aceptaTerminos) errores.aceptaTerminos = t('registro.validacion.terminos')
  return errores
}

/** Mensaje propio de cada campo, en el idioma elegido, para lo que el servidor rechazo. */
function erroresDelServidor(campos, t) {
  const mensajes = {
    nombres: t('registro.validacion.nombres'),
    apellidos: t('registro.validacion.apellidos'),
    correo: t('ingresar.validacion.correo'),
    celular: t('registro.validacion.celular'),
    contrasena: t('registro.validacion.contrasena'),
    aceptaTerminos: t('registro.validacion.terminos'),
  }
  const errores = {}
  Object.keys(campos ?? {}).forEach((campo) => {
    const propio = CAMPOS_SERVIDOR[campo]
    if (propio) errores[propio] = mensajes[propio]
  })
  return errores
}

/**
 * CW-08, pestana "Crear cuenta" (CCPIP-26). Sin genero ni fecha de nacimiento. Nombres y apellidos
 * van por separado como en el Figma; el servidor los guarda juntos. Los terminos se simulan
 * mientras no exista el texto aprobado. `inicial`: datos para volver desde "Cambiar correo".
 */
export default function RegistroFormulario({ inicial = REGISTRO_VACIO, onRegistrado }) {
  const { t } = useTranslation()
  const idTerminos = useId()
  const [form, setForm] = useState(inicial)
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)

  const escribir = (campo) => (e) => {
    const valor = e.target.type === 'checkbox' ? e.target.checked : e.target.value
    setForm((f) => ({ ...f, [campo]: valor }))
  }

  async function handleSubmit(e) {
    e.preventDefault()
    const nuevos = validar(form, t)
    setErrores(nuevos)
    setAlerta(null)
    if (Object.keys(nuevos).length) return

    setEnviando(true)
    const datos = {
      ...form,
      nombres: form.nombres.trim(),
      apellidos: form.apellidos.trim(),
      correo: form.correo.trim(),
      celular: form.celular.trim(),
    }
    try {
      const { email, emailSent } = await registrar(datos)
      onRegistrado({ correo: email, enviado: emailSent, datos: { ...datos, contrasena: '' } })
    } catch (error) {
      setForm((f) => ({ ...f, contrasena: '' }))
      if (error instanceof ApiError && error.code === 'CORREO_YA_REGISTRADO') {
        setErrores({ correo: t('registro.errores.duplicado.titulo') })
        setAlerta({ tono: 'aviso', titulo: t('registro.errores.duplicado.titulo'), descripcion: t('registro.errores.duplicado.descripcion') })
      } else if (error instanceof ApiError && error.code === 'DATOS_INVALIDOS') {
        setErrores(erroresDelServidor(error.body.campos, t))
        setAlerta({ tono: 'error', titulo: t('registro.errores.datos.titulo'), descripcion: t('registro.errores.datos.descripcion') })
      } else if (error instanceof ApiError) {
        setAlerta({ tono: 'error', titulo: t('registro.errores.generico.titulo'), descripcion: mensajeError(error) })
      } else {
        setAlerta({ tono: 'error', titulo: t('ingresar.errores.conexion.titulo'), descripcion: t('comun.errorConexion') })
      }
    } finally {
      setEnviando(false)
    }
  }

  return (
    <>
      <h1 id="titulo-acceso" className="heading-h2 ingresar__titulo">{t('registro.titulo')}</h1>

      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

      <form className="ingresar__formulario" onSubmit={handleSubmit} noValidate>
        <div className="ingresar__fila">
          <Campo
            etiqueta={t('registro.nombres')}
            autoComplete="given-name"
            maxLength={80}
            value={form.nombres}
            onChange={escribir('nombres')}
            error={errores.nombres}
          />
          <Campo
            etiqueta={t('registro.apellidos')}
            autoComplete="family-name"
            maxLength={79}
            value={form.apellidos}
            onChange={escribir('apellidos')}
            error={errores.apellidos}
          />
        </div>
        <Campo
          etiqueta={t('ingresar.correo')}
          type="email"
          autoComplete="email"
          placeholder={t('ingresar.correoEjemplo')}
          maxLength={255}
          value={form.correo}
          onChange={escribir('correo')}
          ayuda={t('registro.correoAyuda')}
          error={errores.correo}
        />
        <Campo
          etiqueta={t('registro.celular')}
          type="tel"
          inputMode="tel"
          autoComplete="tel-national"
          maxLength={20}
          value={form.celular}
          onChange={escribir('celular')}
          ayuda={t('registro.celularAyuda')}
          error={errores.celular}
        />
        <Campo
          etiqueta={t('ingresar.contrasena')}
          tipo="contrasena"
          autoComplete="new-password"
          maxLength={72}
          value={form.contrasena}
          onChange={escribir('contrasena')}
          ayuda={t('registro.contrasenaAyuda')}
          error={errores.contrasena}
        />
        <div className="ingresar__terminos">
          <label className="ingresar__check">
            <input
              type="checkbox"
              checked={form.aceptaTerminos}
              onChange={escribir('aceptaTerminos')}
              aria-invalid={errores.aceptaTerminos ? 'true' : undefined}
              aria-describedby={errores.aceptaTerminos ? idTerminos : undefined}
            />
            {t('registro.terminos')}
          </label>
          {errores.aceptaTerminos && (
            <p id={idTerminos} className="campo__ayuda campo__ayuda--error">
              <img src={iconoAlerta} width="16" height="16" alt="" />
              {errores.aceptaTerminos}
            </p>
          )}
        </div>
        <Boton type="submit" anchoCompleto cargando={enviando}>
          {enviando ? t('registro.creando') : t('registro.crearCuenta')}
        </Boton>
      </form>
    </>
  )
}
