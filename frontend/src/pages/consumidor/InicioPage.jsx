import { useEffect, useState } from 'react'
import iconoArbol from '../../assets/icons/arbol.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoEscudo from '../../assets/icons/sello-completo.svg'
import iconoSinQuimicos from '../../assets/icons/sin-quimicos.svg'
import { obtenerInicio } from '../../api/catalogo.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import TarjetaTienda from '../../components/TarjetaTienda.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'
import './InicioPage.css'

const VALORES = [
  { icono: iconoArbol, titulo: 'Cero deforestación', texto: 'Productos que no vienen de áreas taladas.' },
  { icono: iconoSinQuimicos, titulo: 'Cero pesticidas', texto: 'Sin agroquímicos en cultivo ni transformación.' },
  { icono: iconoComunidad, titulo: 'Hecho por comunidades', texto: 'Elaborado por pueblos indígenas asociados.' },
  { icono: iconoEscudo, titulo: 'Verificado por la Cámara', texto: 'Muestra física y certificados revisados.' },
]

/** CW-01: inicio de la tienda web. */
export default function InicioPage() {
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(false)
  const [pestana, setPestana] = useState('destacados')

  useEffect(() => {
    obtenerInicio().then(setDatos).catch(() => setError(true))
  }, [])

  const productos = datos ? (pestana === 'destacados' ? datos.destacados : datos.novedades) : []

  return (
    <LayoutTienda>
      <main className="inicio-tienda contenedor">
        <section className="hero" aria-labelledby="titulo-hero">
          <div className="hero__texto">
            <p className="overline hero__overline">Hecho por pueblos indígenas del Perú</p>
            <h1 id="titulo-hero" className="hero__titulo">Productos con historia, directo de la Amazonía</h1>
            <p className="hero__descripcion">
              Cacao, café, superfrutos, aceites y artesanías elaborados por comunidades que conservan el bosque. Cada
              compra llega a quienes lo hacen, con el respaldo de la Cámara.
            </p>
            <div className="hero__acciones">
              <Boton tipo="acento" to={RUTAS.buscar}>
                Explorar productos
              </Boton>
              <Boton tipo="secundario" to={RUTAS.comunidades}>
                Conocer a las comunidades
              </Boton>
            </div>
          </div>
          <div className="hero__mosaico" aria-hidden="true">
            <ImagenProducto linea="CACAO" className="hero__img hero__img--alta" />
            <ImagenProducto linea="SUPERFRUTOS" className="hero__img" />
            <ImagenProducto linea="COMUNIDAD" className="hero__img" />
          </div>
        </section>

        <ul className="valores" aria-label="Lo que garantiza cada producto">
          {VALORES.map((v) => (
            <li key={v.titulo} className="valor">
              <span className="valor__icono">
                <img src={v.icono} width="20" height="20" alt="" />
              </span>
              <span>
                <span className="label-m">{v.titulo}</span>
                <span className="caption valor__texto">{v.texto}</span>
              </span>
            </li>
          ))}
        </ul>

        {error && <Alerta tono="error" titulo="No pudimos cargar el inicio" descripcion="Revisa tu conexión e inténtalo otra vez." />}

        {datos?.tiendas.length > 0 && (
          <section className="seccion" aria-labelledby="titulo-comunidades">
            <div className="seccion__encabezado">
              <h2 id="titulo-comunidades" className="seccion__titulo">Conoce a quienes lo hacen</h2>
              <Enlace to={RUTAS.comunidades} className="enlace-marca">Ver todas</Enlace>
            </div>
            <div className="inicio-tienda__tiendas">
              {datos.tiendas.map((t) => (
                <TarjetaTienda key={t.id} tienda={t} />
              ))}
            </div>
          </section>
        )}

        {datos?.lineas.length > 0 && (
          <section className="seccion" aria-labelledby="titulo-lineas">
            <div className="seccion__encabezado">
              <h2 id="titulo-lineas" className="seccion__titulo">Explora por línea</h2>
              <Enlace to={RUTAS.buscar} className="enlace-marca">Ver todas</Enlace>
            </div>
            <ul className="lineas-tienda">
              {datos.lineas.map((l) => (
                <li key={l.codigo}>
                  <Enlace to={`${RUTAS.buscar}?linea=${l.codigo}`} className="linea-tienda">
                    <ImagenProducto linea={l.codigo} className="linea-tienda__img" />
                    <span className="label-m">{l.nombre}</span>
                    <span className="caption">
                      {l.total} {l.total === 1 ? 'producto' : 'productos'}
                    </span>
                  </Enlace>
                </li>
              ))}
            </ul>
          </section>
        )}

        {datos && (
          <section className="seccion" aria-labelledby="titulo-descubrir">
            <div className="seccion__encabezado">
              <div>
                <h2 id="titulo-descubrir" className="seccion__titulo">Productos para descubrir</h2>
                <p className="body-s">Elegidos por las comunidades y recién llegados.</p>
              </div>
              <Boton tipo="secundario" tamano="M" to={RUTAS.buscar}>
                Ver todo
              </Boton>
            </div>
            <Pestanas
              etiqueta="Productos para descubrir"
              valor={pestana}
              onCambiar={setPestana}
              opciones={[
                { valor: 'destacados', etiqueta: 'Destacados' },
                { valor: 'novedades', etiqueta: 'Novedades' },
              ]}
            />
            <ul className="grilla-productos" aria-label={pestana === 'destacados' ? 'Destacados' : 'Novedades'}>
              {productos.map((p) => (
                <li key={p.id}>
                  <TarjetaProducto producto={p} />
                </li>
              ))}
            </ul>
          </section>
        )}

        <section className="mayorista" aria-labelledby="titulo-mayorista">
          <div className="mayorista__texto">
            <span className="etiqueta-producto etiqueta-producto--info">Mayorista</span>
            <h2 id="titulo-mayorista" className="seccion__titulo">¿Compras por volumen para tu tienda, cafetería o para exportar?</h2>
            <p className="body-m">
              Muchos productores venden también por mayor: precio por volumen desde la cantidad mínima que cada uno
              define, con la misma historia y el mismo origen verificado.
            </p>
            <Boton to={`${RUTAS.buscar}?modalidad=MAYORISTA`}>Ver productos mayoristas</Boton>
          </div>
          <ImagenProducto linea="CAFE" className="mayorista__img" />
        </section>
      </main>
    </LayoutTienda>
  )
}
