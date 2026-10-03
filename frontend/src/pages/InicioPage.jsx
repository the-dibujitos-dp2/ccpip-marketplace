import { useEffect, useState } from 'react'
import { fetchPanel } from '../api/panel.js'
import Alerta from '../components/Alerta.jsx'
import Cabecera from '../components/Cabecera.jsx'
import './InicioPage.css'

/** Pantalla de inicio de cada rol. El contenido lo entrega el servidor segun el rol. */
export default function InicioPage({ user, minutosInactividad, onCerrarSesion, cerrando }) {
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
      <Cabecera user={user} onCerrarSesion={onCerrarSesion} cerrando={cerrando} />
      <main className="inicio__contenido">
        <p className="overline">{user.roleName}</p>
        <h1 className="display-l">Hola, {nombre}</h1>

        {panel && (
          <section className="inicio__panel">
            <h2 className="heading-h2">{panel.titulo}</h2>
            <p className="body-l">{panel.descripcion}</p>
          </section>
        )}
        {error && <Alerta tono="error" titulo="No pudimos cargar tu panel" descripcion={error.message} />}

        <p className="caption">
          Por seguridad, tu sesión se cierra tras {minutosInactividad} minutos sin actividad.
        </p>
      </main>
    </div>
  )
}
