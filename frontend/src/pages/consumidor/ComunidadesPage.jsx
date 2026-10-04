import { useEffect, useState } from 'react'
import { listarTiendas } from '../../api/catalogo.js'
import Alerta from '../../components/Alerta.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Enlace from '../../components/Enlace.jsx'
import TarjetaTienda from '../../components/TarjetaTienda.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'

/** CCPIP-153: comunidades productoras con tienda publicada. */
export default function ComunidadesPage() {
  const [tiendas, setTiendas] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    listarTiendas().then(setTiendas).catch(() => setError(true))
  }, [])

  return (
    <LayoutTienda>
      <main className="contenedor comunidades">
        <nav className="migas" aria-label="Migas de pan">
          <Enlace to={RUTAS.catalogo}>Inicio</Enlace> / <span aria-current="page">Comunidades</span>
        </nav>
        <div>
          <p className="overline">Productores asociados a la Cámara</p>
          <h1 className="display-l">Conoce a quienes lo hacen</h1>
          <p className="body-m">Cada comunidad cuenta su historia y vende con origen verificado.</p>
        </div>
        {error && <Alerta tono="error" titulo="No pudimos cargar las comunidades" descripcion="Revisa tu conexión e inténtalo otra vez." />}
        {tiendas?.length === 0 && <EstadoVacio titulo="Aún no hay comunidades publicadas" descripcion="Vuelve pronto." />}
        {tiendas?.length > 0 && (
          <div className="comunidades__grilla">
            {tiendas.map((t) => (
              <TarjetaTienda key={t.id} tienda={t} />
            ))}
          </div>
        )}
      </main>
    </LayoutTienda>
  )
}
