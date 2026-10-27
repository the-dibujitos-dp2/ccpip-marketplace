import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoInfo from '../../assets/icons/info.svg'
import { crearCuenta, puntosAsignables } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Icono from '../../components/Icono.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { navigate, RUTAS } from '../../router.js'
import { cuerpoCuenta, erroresDelServidor, FormularioCuenta, validarCuenta } from './cuentas.jsx'
import '../productor/productor.css'
import './admin.css'

const VACIO = { nombre: '', correo: '', telefono: '', rol: '', puntos: [] }

/**
 * CCPIP-301 y CCPIP-304: el superadministrador crea una cuenta de sub administrador o de administrador
 * de punto de acopio, este con sus puntos. Nadie escribe una contrasena: la persona recibe un enlace
 * de un solo uso para elegir la suya.
 */
export default function NuevaCuentaPage({ path }) {
  const { t } = useTranslation()
  const [f, setF] = useState(VACIO)
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [puntos, setPuntos] = useState(null)
  const [enviando, setEnviando] = useState(false)

  useEffect(() => {
    puntosAsignables().then(setPuntos).catch(() => setPuntos([]))
  }, [])

  const set = (campo) => (e) => {
    setF((actual) => ({ ...actual, [campo]: e.target.value }))
    setErrores((actual) => ({ ...actual, [campo]: undefined }))
  }
  const elegirPuntos = (lista) => {
    setF((actual) => ({ ...actual, puntos: lista }))
    setErrores((actual) => ({ ...actual, puntos: undefined }))
  }

  async function enviar(evento) {
    evento.preventDefault()
    setAlerta(null)
    const e = validarCuenta(f, true, t)
    setErrores(e)
    if (Object.keys(e).length) return
    setEnviando(true)
    try {
      const r = await crearCuenta(cuerpoCuenta(f, true))
      navigate(`${RUTAS.adminCuentas}/${r.cuenta.id}?creada=${r.avisoEnviado ? 'enviado' : 'sin-correo'}`)
    } catch (error) {
      const { campos, general } = erroresDelServidor(error, t)
      setErrores(campos)
      if (general) setAlerta({ tono: 'error', titulo: t('cuentas.errorCrear'), descripcion: general })
      setEnviando(false)
    }
  }

  return (
    <LayoutAdmin path={path} migas={`${t('admin.nav.cuentas')} / ${t('cuentas.nueva')}`} titulo={t('cuentas.nuevaTitulo')}>
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      <form className="tarjeta-panel cuentas__formulario" onSubmit={enviar} noValidate aria-label={t('cuentas.nuevaTitulo')}>
        <FormularioCuenta f={f} set={set} setPuntos={elegirPuntos} errores={errores} nueva puntos={puntos} />
        <div className="cuentas__aviso">
          <Icono src={iconoInfo} tamano={20} />
          <p className="body-s">{t('cuentas.avisoAcceso')}</p>
        </div>
        <div className="cuentas__acciones">
          <Boton tipo="terciario" tamano="M" to={RUTAS.adminCuentas} disabled={enviando}>
            {t('comun.cancelar')}
          </Boton>
          <Boton type="submit" tamano="M" cargando={enviando}>
            {t('cuentas.crear')}
          </Boton>
        </div>
      </form>
    </LayoutAdmin>
  )
}
