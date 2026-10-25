import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchPanel } from '../api/panel.js'
import Alerta from '../components/Alerta.jsx'
import Boton from '../components/Boton.jsx'
import Cargando from '../components/Cargando.jsx'
import { mensajeError } from '../i18n/errores.js'
import { RUTAS } from '../router.js'
import './CuentaPage.css'

/** Pantalla de inicio de cada rol. El contenido lo entrega el servidor segun el rol. */
export default function CuentaPage({ user, minutosInactividad }) {
  const { t } = useTranslation()
  const [panel, setPanel] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    fetchPanel(user.role)
      .then(setPanel)
      .catch((e) => {
        // 401 lo maneja App (sesion expirada); aqui solo otros errores
        if (e.status !== 401) setError(e)
      })
  }, [user.role])

  const nombre = user.fullName.split(' ')[0]

  return (
    <div className="inicio">
      <main className="inicio__contenido">
        <p className="overline">{t(`roles.${user.role}`, { defaultValue: user.roleName })}</p>
        <h1 className="display-l">{t('cuenta.hola', { nombre })}</h1>

        {panel && (
          <section className="inicio__panel">
            <h2 className="heading-h2">{t(`cuenta.panel.${user.role}.titulo`, { defaultValue: panel.titulo })}</h2>
            <p className="body-l">{t(`cuenta.panel.${user.role}.descripcion`, { defaultValue: panel.descripcion })}</p>
          </section>
        )}
        {error && <Alerta tono="error" titulo={t('cuenta.error')} descripcion={mensajeError(error)} />}
        {!panel && !error && <Cargando />}

        {user.role === 'ADMIN_PUNTO' && (
          <div className="inicio__acciones">
            <Boton tamano="M" to={RUTAS.acopioPedidos}>{t('cuenta.verPedidos')}</Boton>
            <Boton tamano="M" tipo="secundario" to={RUTAS.acopioMovimientos}>{t('cuenta.verMovimientos')}</Boton>
          </div>
        )}

        {user.role === 'DISTRIBUIDOR' && (
          <div className="inicio__acciones">
            <Boton tamano="M" to={RUTAS.distribuidorPedidos}>{t('cuenta.verPedidosDisponibles')}</Boton>
          </div>
        )}

        {user.role === 'SUPER_ADMIN' && (
          <div className="inicio__acciones">
            <Boton tamano="M" to={RUTAS.admin}>{t('cuenta.verTablero')}</Boton>
          </div>
        )}

        <p className="caption">{t('cuenta.seguridad', { minutos: minutosInactividad })}</p>
      </main>
    </div>
  )
}
