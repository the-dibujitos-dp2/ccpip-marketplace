import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { guardarMisDatos, obtenerMisDatos } from '../../api/cuenta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { erroresPorCampo, mensajeError } from '../../i18n/errores.js'
import { useSesion } from '../../sesion.js'
import LateralCuenta from './LateralCuenta.jsx'
import './MisPedidosPage.css'
import './MisDatosPage.css'

const CELULAR = /^$|^\+?[0-9][0-9 ]{5,18}$/

/**
 * CW-13 · Mi cuenta › Datos personales. CCPIP-400: el consumidor consulta sus datos personales y de
 * contacto; CCPIP-113: edita nombres, apellidos y celular, con las reglas del registro (CW-08). Sin
 * genero ni fecha de nacimiento. El correo es su usuario para ingresar: se muestra, no se cambia.
 * Ubicaciones, puntos y "Cambiar contrasena" de CW-13 llegan con sus historias.
 */
export default function MisDatosPage() {
  const { t } = useTranslation()
  const { actualizarUsuario } = useSesion()
  const [datos, setDatos] = useState(null)
  const [form, setForm] = useState(null)
  const [errorCarga, setErrorCarga] = useState(null)
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [guardando, setGuardando] = useState(false)

  function aplicar(d) {
    setDatos(d)
    setForm({ nombres: d.nombres, apellidos: d.apellidos, celular: d.celular ?? '' })
  }

  useEffect(() => {
    obtenerMisDatos().then(aplicar).catch((e) => e.status !== 401 && setErrorCarga(e))
  }, [])

  function cambiar(campo) {
    return (e) => {
      setForm((f) => ({ ...f, [campo]: e.target.value }))
      setErrores((x) => ({ ...x, [campo]: undefined }))
      setAlerta(null)
    }
  }

  function guardar(e) {
    e.preventDefault()
    const nuevos = {}
    if (!form.nombres.trim()) nuevos.nombres = t('misDatos.errores.nombres')
    else if (form.nombres.trim().length > 80) nuevos.nombres = t('misDatos.errores.largo', { maximo: 80 })
    if (!form.apellidos.trim()) nuevos.apellidos = t('misDatos.errores.apellidos')
    else if (form.apellidos.trim().length > 79) nuevos.apellidos = t('misDatos.errores.largo', { maximo: 79 })
    if (!CELULAR.test(form.celular.trim())) nuevos.celular = t('misDatos.errores.celular')
    setErrores(nuevos)
    if (Object.keys(nuevos).length > 0) return
    setGuardando(true)
    guardarMisDatos({ nombres: form.nombres.trim(), apellidos: form.apellidos.trim(), celular: form.celular.trim() })
      .then((d) => {
        aplicar(d)
        // El nombre de la cabecera y del menu cambia sin volver a ingresar
        actualizarUsuario?.({ fullName: `${d.nombres} ${d.apellidos}`.trim() })
        setAlerta({ tono: 'exito', titulo: t('misDatos.guardado') })
      })
      .catch((err) => {
        if (err.body?.campos) setErrores(erroresPorCampo(err))
        setAlerta({ tono: 'error', titulo: t('misDatos.errorGuardar'), descripcion: mensajeError(err) })
      })
      .finally(() => setGuardando(false))
  }

  return (
    <LayoutTienda>
      <main className="contenedor mis-pedidos">
        <h1 className="mis-pedidos__titulo">{t('misPedidos.miCuenta')}</h1>
        <div className="mis-pedidos__cuerpo">
          <LateralCuenta activo="datos" />
          <section className="mis-pedidos__contenido" aria-busy={!datos && !errorCarga}>
            {errorCarga && <Alerta tono="error" titulo={t('misDatos.errorCargar')} descripcion={mensajeError(errorCarga, 'comun.errorConexion')} />}
            {!datos && !errorCarga && <Cargando />}
            {form && (
              <form className="mis-datos" onSubmit={guardar} aria-labelledby="titulo-mis-datos" noValidate>
                <h2 id="titulo-mis-datos" className="mis-datos__titulo">{t('misDatos.titulo')}</h2>
                {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
                <div className="mis-datos__campos">
                  <Campo etiqueta={t('misDatos.nombres')} value={form.nombres} onChange={cambiar('nombres')} error={errores.nombres}
                    maxLength={80} autoComplete="given-name" />
                  <Campo etiqueta={t('misDatos.apellidos')} value={form.apellidos} onChange={cambiar('apellidos')} error={errores.apellidos}
                    maxLength={79} autoComplete="family-name" />
                  <Campo etiqueta={t('misDatos.correo')} value={datos.correo} readOnly type="email"
                    ayuda={t(datos.correoConfirmado ? 'misDatos.correoAyuda' : 'misDatos.correoSinConfirmar')} />
                  <Campo etiqueta={t('misDatos.celular')} value={form.celular} onChange={cambiar('celular')} error={errores.celular}
                    inputMode="tel" maxLength={20} autoComplete="tel" ayuda={t('misDatos.celularAyuda')} />
                </div>
                <div className="mis-datos__acciones">
                  <Boton type="submit" tamano="M" disabled={guardando}>
                    {guardando ? t('misDatos.guardando') : t('misDatos.guardar')}
                  </Boton>
                </div>
              </form>
            )}
          </section>
        </div>
      </main>
    </LayoutTienda>
  )
}
