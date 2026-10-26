import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarTiendas } from '../../api/catalogo.js'
import Alerta from '../../components/Alerta.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Enlace from '../../components/Enlace.jsx'
import TarjetaTienda from '../../components/TarjetaTienda.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'

/** CCPIP-153: comunidades productoras con tienda publicada. */
export default function ComunidadesPage() {
  const { t } = useTranslation()
  const [tiendas, setTiendas] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    listarTiendas().then(setTiendas).catch(() => setError(true))
  }, [])

  return (
    <LayoutTienda>
      <main className="contenedor comunidades">
        <nav className="migas" aria-label={t('comun.migas')}>
          <Enlace to={RUTAS.catalogo}>{t('comun.inicio')}</Enlace> / <span aria-current="page">{t('comunidades.titulo')}</span>
        </nav>
        <div>
          <p className="overline">{t('comunidades.overline')}</p>
          <h1 className="display-l">{t('inicio.comunidades')}</h1>
          <p className="body-m">{t('comunidades.bajada')}</p>
        </div>
        {error && <Alerta tono="error" titulo={t('comunidades.error')} descripcion={t('comun.errorConexion')} />}
        {!tiendas && !error && <Cargando />}
        {tiendas?.length === 0 && <EstadoVacio titulo={t('comunidades.vacio')} descripcion={t('comun.vuelvePronto')} />}
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
