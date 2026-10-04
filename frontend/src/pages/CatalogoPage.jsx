import { useEffect, useState } from 'react'
import { buscarProductos } from '../api/catalogo.js'
import Alerta from '../components/Alerta.jsx'
import Boton from '../components/Boton.jsx'
import EstadoVacio from '../components/EstadoVacio.jsx'
import Paginacion from '../components/Paginacion.jsx'
import TarjetaProducto from '../components/TarjetaProducto.jsx'
import { navigate, RUTAS } from '../router.js'
import './CatalogoPage.css'

/** Catalogo y busqueda por nombre (CCPIP-52). El termino y la pagina viven en la URL. */
export default function CatalogoPage({ q, pagina }) {
  const [resultado, setResultado] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let vigente = true
    // Sin esto, durante la carga se verian los resultados de la busqueda anterior bajo el titulo nuevo
    setResultado(null)
    setError(null)
    buscarProductos({ q, pagina })
      .then((datos) => vigente && setResultado(datos))
      .catch((e) => vigente && setError(e))
    return () => {
      vigente = false
    }
  }, [q, pagina])

  function irAPagina(nueva) {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    if (nueva > 0) params.set('pagina', String(nueva))
    const query = params.toString()
    navigate(query ? `${RUTAS.catalogo}?${query}` : RUTAS.catalogo)
    window.scrollTo?.(0, 0)
  }

  return (
    <main className="catalogo">
      <div className="catalogo__encabezado">
        <p className="overline">Catálogo</p>
        <h1 className="display-l">{q ? `Resultados para “${q}”` : 'Explora productos con historia'}</h1>
        {!q && <p className="body-l">Directo de las comunidades amazónicas, con origen verificado por la CCPIP.</p>}
      </div>

      {error && (
        <Alerta tono="error" titulo="No pudimos cargar el catálogo" descripcion="Revisa tu conexión e inténtalo otra vez." />
      )}

      {resultado && resultado.total === 0 && (
        <EstadoVacio
          titulo={q ? `No encontramos productos para “${q}”` : 'Aún no hay productos disponibles'}
          descripcion={q ? 'Prueba con otra palabra, por ejemplo cacao, café o aceite.' : 'Vuelve pronto: las comunidades están preparando sus productos.'}
        >
          {q && (
            <Boton tamano="M" onClick={() => navigate(RUTAS.catalogo)}>
              Ver todo el catálogo
            </Boton>
          )}
        </EstadoVacio>
      )}

      {resultado && resultado.total > 0 && (
        <>
          <ul className="catalogo__grilla" aria-label="Productos">
            {resultado.items.map((producto) => (
              <li key={producto.id}>
                <TarjetaProducto producto={producto} />
              </li>
            ))}
          </ul>
          <Paginacion {...resultado} onCambiar={irAPagina} />
        </>
      )}
    </main>
  )
}
