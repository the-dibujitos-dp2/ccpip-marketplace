import { RUTAS } from '../../router.js'
import Enlace from '../Enlace.jsx'
import Logotipo from '../Logotipo.jsx'
import './PieDePagina.css'

/** Pie de pagina web (Figma 66:513). Solo enlaces a paginas que ya existen. */
export default function PieDePagina() {
  return (
    <footer className="pie">
      <div className="contenedor">
        <div className="pie__columnas">
          <div className="pie__marca">
            <Logotipo inverso />
            <p>
              Un canal de la Cámara de Comercio de los Pueblos Indígenas del Perú para llevar productos con historia,
              directo de las comunidades, a todo el país.
            </p>
          </div>
          <nav aria-label="Comprar">
            <p className="pie__titulo">Comprar</p>
            <Enlace to={RUTAS.buscar}>Explorar productos</Enlace>
            <Enlace to={RUTAS.comunidades}>Comunidades</Enlace>
            <Enlace to={`${RUTAS.buscar}?orden=novedades`}>Novedades</Enlace>
          </nav>
        </div>
        <p className="pie__legal">© 2026 CCPIP · Prototipo académico con datos de prueba</p>
      </div>
    </footer>
  )
}
