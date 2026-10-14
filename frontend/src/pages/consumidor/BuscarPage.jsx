import { useEffect, useState } from 'react'
import iconoCerrar from '../../assets/icons/cerrar.svg'
import iconoChevron from '../../assets/icons/chevron-abajo.svg'
import { buscarProductos } from '../../api/catalogo.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import Interruptor from '../../components/Interruptor.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { navigate, RUTAS } from '../../router.js'
import '../../components/Campo.css'
import './BuscarPage.css'

const ORDENES = [
  { valor: 'RELEVANCIA', etiqueta: 'Relevancia' },
  { valor: 'NOVEDADES', etiqueta: 'Novedades' },
  { valor: 'PRECIO_ASC', etiqueta: 'Precio: menor a mayor' },
  { valor: 'PRECIO_DESC', etiqueta: 'Precio: mayor a menor' },
]

/** Lee los filtros de la URL: el estado de la busqueda se puede compartir y "atras" funciona. */
export function leerFiltros(search) {
  const p = new URLSearchParams(search)
  return {
    q: (p.get('q') ?? '').trim(),
    lineas: p.getAll('linea'),
    regiones: p.getAll('region'),
    modalidad: p.get('modalidad') ?? '',
    tienda: p.get('tienda') ?? '',
    precioMin: p.get('precioMin') ?? '',
    precioMax: p.get('precioMax') ?? '',
    disponibles: p.get('disponibles') !== 'false',
    orden: (p.get('orden') ?? 'RELEVANCIA').toUpperCase(),
    pagina: Math.max(0, Number(p.get('pagina')) || 0),
  }
}

export function escribirFiltros(f) {
  const p = new URLSearchParams()
  if (f.q) p.set('q', f.q)
  f.lineas.forEach((l) => p.append('linea', l))
  f.regiones.forEach((r) => p.append('region', r))
  if (f.modalidad) p.set('modalidad', f.modalidad)
  if (f.tienda) p.set('tienda', f.tienda)
  if (f.precioMin !== '') p.set('precioMin', f.precioMin)
  if (f.precioMax !== '') p.set('precioMax', f.precioMax)
  if (!f.disponibles) p.set('disponibles', 'false')
  if (f.orden && f.orden !== 'RELEVANCIA') p.set('orden', f.orden.toLowerCase())
  if (f.pagina > 0) p.set('pagina', String(f.pagina))
  const query = p.toString()
  return query ? `${RUTAS.buscar}?${query}` : RUTAS.buscar
}

/** CW-02: catalogo y busqueda con filtros (CCPIP-52, CCPIP-54, CCPIP-165). */
export default function BuscarPage({ search }) {
  const filtros = leerFiltros(search)
  const [resultado, setResultado] = useState(null)
  const [error, setError] = useState(null)
  const [precio, setPrecio] = useState({ min: filtros.precioMin, max: filtros.precioMax })

  useEffect(() => {
    let vigente = true
    setResultado(null)
    setError(null)
    setPrecio({ min: filtros.precioMin, max: filtros.precioMax })
    buscarProductos({ ...filtros, modalidad: filtros.modalidad || undefined, tienda: filtros.tienda || undefined })
      .then((d) => vigente && setResultado(d))
      .catch((e) => vigente && setError(e))
    return () => {
      vigente = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search])

  // El rango de precio se aplica solo al dejar de escribir
  useEffect(() => {
    if (precio.min === filtros.precioMin && precio.max === filtros.precioMax) return undefined
    const t = setTimeout(() => aplicar({ precioMin: precio.min, precioMax: precio.max }), 700)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [precio.min, precio.max])

  /** Cambiar un filtro vuelve a la primera pagina. */
  function aplicar(cambios) {
    navigate(escribirFiltros({ ...filtros, pagina: 0, ...cambios }))
  }

  function alternar(lista, valor) {
    return lista.includes(valor) ? lista.filter((v) => v !== valor) : [...lista, valor]
  }

  const facetas = resultado?.facetas
  const nombreTienda = facetas?.comunidades.find((c) => c.codigo === filtros.tienda)?.nombre
  const chips = [
    ...filtros.lineas.map((l) => ({ clave: `l-${l}`, texto: facetas?.lineas.find((x) => x.codigo === l)?.nombre ?? l, quitar: { lineas: filtros.lineas.filter((x) => x !== l) } })),
    ...filtros.regiones.map((r) => ({ clave: `r-${r}`, texto: r, quitar: { regiones: filtros.regiones.filter((x) => x !== r) } })),
    ...(filtros.modalidad ? [{ clave: 'm', texto: filtros.modalidad === 'MAYORISTA' ? 'Mayorista' : 'Minorista', quitar: { modalidad: '' } }] : []),
    ...(filtros.tienda ? [{ clave: 't', texto: nombreTienda ?? 'Comunidad', quitar: { tienda: '' } }] : []),
    ...(filtros.precioMin !== '' || filtros.precioMax !== ''
      ? [{ clave: 'p', texto: `S/ ${filtros.precioMin || 0} – ${filtros.precioMax || '…'}`, quitar: { precioMin: '', precioMax: '' } }]
      : []),
  ]

  return (
    <LayoutTienda termino={filtros.q}>
      <main className="buscar contenedor">
        <nav className="migas" aria-label="Migas de pan">
          <Enlace to={RUTAS.catalogo}>Inicio</Enlace> / <Enlace to={RUTAS.buscar}>Buscar</Enlace>
          {filtros.q && <span> / “{filtros.q}”</span>}
        </nav>

        <div className="buscar__encabezado">
          <div>
            <h1 className="display-l">{filtros.q ? `Resultados para “${filtros.q}”` : 'Explora productos con historia'}</h1>
            {resultado && (
              <p className="body-m">
                {resultado.total} {resultado.total === 1 ? 'producto' : 'productos'} de {resultado.totalComunidades}{' '}
                {resultado.totalComunidades === 1 ? 'comunidad' : 'comunidades'}
              </p>
            )}
          </div>
          <label className="buscar__orden">
            <span className="visually-hidden">Ordenar</span>
            <select value={filtros.orden} onChange={(e) => aplicar({ orden: e.target.value })}>
              {ORDENES.map((o) => (
                <option key={o.valor} value={o.valor}>
                  Ordenar: {o.etiqueta}
                </option>
              ))}
            </select>
            <Icono src={iconoChevron} tamano={16} className="buscar__orden-chevron" />
          </label>
        </div>

        <div className="buscar__cuerpo">
          <aside className="filtros" aria-label="Filtros">
            <div className="filtros__titulo">
              <h2 className="heading-h4">Filtros</h2>
              {chips.length > 0 && (
                <button type="button" className="enlace-marca filtros__limpiar" onClick={() => navigate(escribirFiltros({ ...leerFiltros(''), q: filtros.q }))}>
                  Limpiar
                </button>
              )}
            </div>

            {facetas?.lineas.length > 0 && (
              <fieldset className="filtros__grupo">
                <legend>Línea de producto</legend>
                {facetas.lineas.map((l) => (
                  <label key={l.codigo} className="casilla">
                    <input type="checkbox" checked={filtros.lineas.includes(l.codigo)} onChange={() => aplicar({ lineas: alternar(filtros.lineas, l.codigo) })} />
                    <span>{l.nombre}</span>
                    <span className="casilla__total">{l.total}</span>
                  </label>
                ))}
              </fieldset>
            )}

            {facetas?.modalidades.length > 0 && (
              <fieldset className="filtros__grupo">
                <legend>Modalidad de compra</legend>
                {facetas.modalidades.map((m) => (
                  <label key={m.codigo} className="casilla">
                    <input
                      type="checkbox"
                      checked={filtros.modalidad === m.codigo}
                      onChange={() => aplicar({ modalidad: filtros.modalidad === m.codigo ? '' : m.codigo })}
                    />
                    <span>{m.nombre}</span>
                    <span className="casilla__total">{m.total}</span>
                  </label>
                ))}
              </fieldset>
            )}

            {facetas?.regiones.length > 0 && (
              <fieldset className="filtros__grupo">
                <legend>Región</legend>
                {facetas.regiones.map((r) => (
                  <label key={r.codigo} className="casilla">
                    <input type="checkbox" checked={filtros.regiones.includes(r.codigo)} onChange={() => aplicar({ regiones: alternar(filtros.regiones, r.codigo) })} />
                    <span>{r.nombre}</span>
                    <span className="casilla__total">{r.total}</span>
                  </label>
                ))}
              </fieldset>
            )}

            <form
              className="filtros__grupo"
              onSubmit={(e) => {
                e.preventDefault()
                aplicar({ precioMin: precio.min, precioMax: precio.max })
              }}
            >
              <fieldset>
                <legend>Precio (S/)</legend>
                <div className="filtros__precio">
                  <label>
                    <span className="visually-hidden">Precio mínimo</span>
                    <input type="number" min="0" step="1" inputMode="decimal" placeholder="Mín." value={precio.min} onChange={(e) => setPrecio({ ...precio, min: e.target.value })} />
                  </label>
                  <label>
                    <span className="visually-hidden">Precio máximo</span>
                    <input type="number" min="0" step="1" inputMode="decimal" placeholder="Máx." value={precio.max} onChange={(e) => setPrecio({ ...precio, max: e.target.value })} />
                  </label>
                </div>
                {/* Enter tambien aplica; sin boton visible, como en CW-02 */}
                <button type="submit" className="visually-hidden">Aplicar precio</button>
              </fieldset>
            </form>

            {facetas?.comunidades.length > 0 && (
              <label className="filtros__grupo">
                <span className="filtros__leyenda">Comunidad o pueblo</span>
                <span className="campo__selector">
                  <select className="campo__control" value={filtros.tienda} onChange={(e) => aplicar({ tienda: e.target.value })}>
                    <option value="">Todas</option>
                    {facetas.comunidades.map((c) => (
                      <option key={c.codigo} value={c.codigo}>
                        {c.nombre} ({c.total})
                      </option>
                    ))}
                  </select>
                  <Icono src={iconoChevron} tamano={20} className="campo__chevron" />
                </span>
              </label>
            )}

            <div className="filtros__grupo">
              <Interruptor etiqueta="Mostrar solo disponibles" activo={filtros.disponibles} onCambiar={(v) => aplicar({ disponibles: v })} />
            </div>
          </aside>

          <section className="buscar__resultados" aria-label="Resultados">
            {chips.length > 0 && (
              <ul className="filtros-activos" aria-label="Filtros activos">
                {chips.map((c) => (
                  <li key={c.clave}>
                    <button type="button" className="chip chip--activo" onClick={() => aplicar(c.quitar)} aria-label={`Quitar filtro ${c.texto}`}>
                      {c.texto}
                      <Icono src={iconoCerrar} tamano={14} />
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {/* CCPIP-363: un enlace a una comunidad que ya no existe o pauso su tienda no es un error de red */}
            {error?.code === 'COMUNIDAD_INVALIDA' ? (
              <EstadoVacio titulo="Esa comunidad ya no está disponible" descripcion="Puede que haya pausado su tienda o que el enlace esté mal.">
                <Boton tamano="M" onClick={() => aplicar({ tienda: '' })}>
                  Quitar el filtro de comunidad
                </Boton>
              </EstadoVacio>
            ) : (
              error && <Alerta tono="error" titulo="No pudimos cargar el catálogo" descripcion="Revisa tu conexión e inténtalo otra vez." />
            )}

            {resultado?.total === 0 && (
              <EstadoVacio
                titulo={filtros.q ? `No encontramos productos para “${filtros.q}”` : 'No hay productos con estos filtros'}
                descripcion="Prueba con otra palabra o quita algún filtro, por ejemplo cacao, café o aceite."
              >
                <Boton tamano="M" to={RUTAS.buscar}>
                  Ver todo el catálogo
                </Boton>
              </EstadoVacio>
            )}

            {resultado?.total > 0 && (
              <>
                <ul className="grilla-productos buscar__grilla" aria-label="Productos">
                  {resultado.items.map((p) => (
                    <li key={p.id}>
                      <TarjetaProducto producto={p} />
                    </li>
                  ))}
                </ul>
                <Paginacion
                  pagina={resultado.pagina}
                  totalPaginas={resultado.totalPaginas}
                  onCambiar={(n) => {
                    navigate(escribirFiltros({ ...filtros, pagina: n }))
                    window.scrollTo?.(0, 0)
                  }}
                />
              </>
            )}
          </section>
        </div>
      </main>
    </LayoutTienda>
  )
}
