import { useEffect, useRef, useState } from 'react'
import iconoCamara from '../../assets/icons/camara.svg'
import iconoCerrar from '../../assets/icons/cerrar.svg'
import iconoIa from '../../assets/icons/ia.svg'
import iconoInfo from '../../assets/icons/info-pequeno.svg'
import { obtenerCatalogos } from '../../api/catalogo.js'
import { eliminarImagen, marcarPrincipal, proponerDescripcion, subirImagen } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Chips from '../../components/Chips.jsx'
import Icono from '../../components/Icono.jsx'
import OpcionTarjeta from '../../components/OpcionTarjeta.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import ZonaArchivos from '../../components/ZonaArchivos.jsx'
import { precio } from '../../formato.js'
import './Editor.css'

export const MAX_FOTOS = 6

const MODALIDADES = [
  { valor: 'MINORISTA', etiqueta: 'Solo minorista' },
  { valor: 'MAYORISTA', etiqueta: 'Solo mayorista' },
  { valor: 'AMBAS', etiqueta: 'Minorista y mayorista' },
]

function datosIniciales(p) {
  return {
    nombre: p?.nombre ?? '',
    descripcion: p?.descripcion ?? '',
    descripcionAsistida: p?.descripcionAsistida ?? false,
    lineas: p?.lineas ?? [],
    unidad: p?.unidad ?? '',
    contenido: p?.contenido ?? '',
    modalidad: p?.modalidad ?? 'MINORISTA',
    precio: p?.precio ?? '',
    precioMayorista: p?.precioMayorista ?? '',
    cantidadMinimaMayorista: p?.cantidadMinimaMayorista ?? '',
    esMateriaPrima: p?.esMateriaPrima ?? false,
  }
}

/** Convierte el formulario en el cuerpo que espera la API (numeros o null). */
export function aCuerpo(d) {
  const num = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
  return {
    ...d,
    nombre: d.nombre.trim(),
    unidad: d.unidad || null,
    precio: num(d.precio),
    precioMayorista: num(d.precioMayorista),
    cantidadMinimaMayorista: num(d.cantidadMinimaMayorista),
  }
}

/** Errores que se pueden detectar en pantalla antes de ir al servidor. */
export function validarDatos(d, { paraPublicar = false } = {}) {
  const e = {}
  if (!d.nombre.trim()) e.nombre = 'Escribe el nombre del producto'
  const minorista = d.modalidad !== 'MAYORISTA'
  const mayorista = d.modalidad !== 'MINORISTA'
  if (d.precio !== '' && !(Number(d.precio) >= 0.1)) e.precio = 'El precio debe ser mayor a S/ 0.10'
  if (d.precioMayorista !== '' && !(Number(d.precioMayorista) >= 0.1)) e.precioMayorista = 'El precio debe ser mayor a S/ 0.10'
  if (minorista && mayorista && d.precio !== '' && d.precioMayorista !== '' && Number(d.precioMayorista) >= Number(d.precio)) {
    e.precioMayorista = 'Debe ser menor que el precio minorista'
  }
  if (mayorista && d.precioMayorista !== '' && !(Number(d.cantidadMinimaMayorista) >= 2)) e.cantidadMinimaMayorista = 'Indica desde qué cantidad (2 o más)'
  if (paraPublicar) {
    if (!d.lineas.length) e.lineas = 'Elige al menos una línea'
    if (!d.unidad) e.unidad = 'Elige la unidad de medida'
    if (minorista && d.precio === '') e.precio = 'Escribe el precio minorista'
    if (mayorista && d.precioMayorista === '') e.precioMayorista = 'Escribe el precio mayorista'
  }
  return e
}

/** PW-04 · paso 1 "Datos": todo lo que el comprador ve del producto (CCPIP-40, 45, 132, 44). */
export default function PasoDatos({ producto, tienda, asegurarProducto, onGuardar, onSiguiente, onImagenes }) {
  const [catalogos, setCatalogos] = useState({ lineas: [], unidades: [], comisionPorcentaje: null })
  const [d, setD] = useState(() => datosIniciales(producto))
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [guardando, setGuardando] = useState(false)
  const [subidas, setSubidas] = useState([])
  const [ia, setIa] = useState({ estado: 'inicial' })

  useEffect(() => {
    obtenerCatalogos().then(setCatalogos).catch(() => {})
  }, [])
  // Al pasar a otro producto se limpia lo pendiente; no al nacer el borrador (de nuevo a :id)
  const anterior = useRef(producto?.id)
  useEffect(() => {
    setD(datosIniciales(producto))
    if (anterior.current !== undefined) {
      setSubidas([])
      setAlerta(null)
      setIa({ estado: 'inicial' })
    }
    anterior.current = producto?.id
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [producto?.id])

  const imagenes = producto?.imagenes ?? []
  // Un producto publicado no vuelve a borrador: lo que se guarda se ve al instante (CCPIP-47)
  const publicado = producto?.estado === 'PUBLICADO'
  const set = (campo) => (e) => setD({ ...d, [campo]: e?.target ? (e.target.type === 'checkbox' ? e.target.checked : e.target.value) : e })

  function elegirLineas(lineas) {
    const primera = catalogos.lineas.find((l) => l.codigo === lineas[0])
    // CCPIP-45: la linea sugiere la unidad, si el productor aun no eligio una
    setD({ ...d, lineas, unidad: d.unidad || primera?.unidadSugerida || '' })
  }

  async function guardar(siguiente) {
    const e = validarDatos(d)
    setErrores(e)
    if (Object.keys(e).length) {
      setAlerta({ tono: 'error', titulo: 'Revisa los datos marcados', descripcion: Object.values(e).join(' · ') })
      return
    }
    setGuardando(true)
    setAlerta(null)
    try {
      await onGuardar(aCuerpo(d))
      if (siguiente) onSiguiente()
      else if (publicado) setAlerta({ tono: 'exito', titulo: 'Cambios guardados', descripcion: 'Ya se ven en el catálogo y en tu tienda.' })
      else setAlerta({ tono: 'exito', titulo: 'Borrador guardado', descripcion: 'Puedes seguir más tarde desde Mis productos.' })
    } catch (err) {
      setErrores(err.body?.campos ?? {})
      setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos guardar', descripcion: err.body?.detalles?.join(' · ') })
    } finally {
      setGuardando(false)
    }
  }

  // ---------- Fotos (CCPIP-44): una peticion por archivo, con reintento ----------

  async function subir(archivos) {
    let id
    try {
      id = await asegurarProducto(aCuerpo(d))
    } catch (err) {
      setErrores({ nombre: 'Escribe el nombre del producto antes de agregar fotos' })
      setAlerta({ tono: 'error', titulo: err.message ?? 'Guarda el producto antes de agregar fotos' })
      return
    }
    const libres = MAX_FOTOS - imagenes.length - subidas.filter((s) => s.estado !== 'error').length
    const tomados = archivos.slice(0, Math.max(0, libres))
    if (archivos.length > tomados.length) {
      setAlerta({ tono: 'aviso', titulo: `Un producto puede tener hasta ${MAX_FOTOS} fotos`, descripcion: 'Elimina alguna para agregar otras.' })
    }
    for (const archivo of tomados) {
      await subirUno(id, archivo)
    }
    onImagenes(id)
  }

  async function subirUno(id, archivo, clave = `${archivo.name}-${archivo.size}-${Date.now()}`) {
    if (archivo.size > 10 * 1024 * 1024) {
      setSubidas((s) => [...s.filter((x) => x.clave !== clave), { clave, archivo, estado: 'error', mensaje: 'Pesa más de 10 MB' }])
      return
    }
    setSubidas((s) => [...s.filter((x) => x.clave !== clave), { clave, archivo, estado: 'subiendo' }])
    try {
      await subirImagen(archivo, { tipo: 'PRODUCTO', productoId: id })
      setSubidas((s) => s.filter((x) => x.clave !== clave))
    } catch (err) {
      setSubidas((s) => s.map((x) => (x.clave === clave ? { ...x, estado: 'error', mensaje: err.message ?? 'Se cortó la conexión' } : x)))
    }
  }

  async function reintentar(s) {
    const id = await asegurarProducto(aCuerpo(d))
    await subirUno(id, s.archivo, s.clave)
    onImagenes(id)
  }

  async function quitar(imagen) {
    await eliminarImagen(imagen.id)
    onImagenes(producto.id)
  }

  async function principal(imagen) {
    await marcarPrincipal(imagen.id)
    onImagenes(producto.id)
  }

  // ---------- Descripcion asistida (CCPIP-48, RNF-29) ----------

  async function sugerir() {
    if (!d.nombre.trim()) {
      setErrores({ ...errores, nombre: 'Escribe el nombre para que la IA tenga de dónde partir' })
      return
    }
    setIa({ estado: 'cargando' })
    try {
      const propuesta = await proponerDescripcion({ productoId: producto?.id ?? null, nombre: d.nombre.trim(), lineas: d.lineas, contenido: d.contenido || null })
      setIa({ estado: 'propuesta', texto: propuesta.texto })
    } catch (err) {
      setIa({ estado: err.code === 'IA_NO_DISPONIBLE' ? 'no-disponible' : 'error', mensaje: err.message })
    }
  }

  function aceptarDescripcion(editar) {
    setD({ ...d, descripcion: ia.texto, descripcionAsistida: true })
    setIa({ estado: 'aceptada' })
    if (editar) setTimeout(() => document.getElementById('descripcion-producto')?.focus(), 0)
  }

  const mayorista = d.modalidad !== 'MINORISTA'
  const minorista = d.modalidad !== 'MAYORISTA'
  const principalImg = imagenes.find((i) => i.principal) ?? imagenes[0]
  const monto = minorista ? Number(d.precio) : Number(d.precioMayorista)
  const comision = catalogos.comisionPorcentaje

  return (
    <div className="editor__datos">
      <div className="tarjeta-panel editor__formulario">
        {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

        <section className="editor__fotos" aria-labelledby="titulo-fotos">
          <h2 id="titulo-fotos" className="heading-h4">Fotos</h2>
          <ul className="editor__miniaturas">
            {imagenes.map((img) => (
              <li key={img.id} className={`editor__miniatura${img.principal ? ' editor__miniatura--principal' : ''}`}>
                <img src={img.urlMiniatura} alt="Foto del producto" />
                {img.principal ? (
                  <span className="editor__principal">Principal</span>
                ) : (
                  <button type="button" className="editor__accion-foto editor__accion-foto--principal" onClick={() => principal(img)}>
                    Hacer principal
                  </button>
                )}
                <button type="button" className="editor__quitar" aria-label="Quitar foto" onClick={() => quitar(img)}>
                  <Icono src={iconoCerrar} tamano={14} />
                </button>
              </li>
            ))}
            {subidas.map((s) => (
              <li key={s.clave} className={`editor__miniatura editor__miniatura--${s.estado}`}>
                <span className="caption">{s.estado === 'subiendo' ? 'Subiendo…' : s.mensaje}</span>
                {s.estado === 'error' && (
                  <button type="button" className="editor__accion-foto" onClick={() => reintentar(s)}>
                    Reintentar
                  </button>
                )}
              </li>
            ))}
            {imagenes.length + subidas.length < MAX_FOTOS && (
              <li className="editor__miniatura editor__miniatura--agregar">
                <label>
                  <Icono src={iconoCamara} tamano={20} />
                  Agregar
                  <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(e) => { subir(Array.from(e.target.files ?? [])); e.target.value = '' }} />
                </label>
              </li>
            )}
          </ul>
          {imagenes.length < MAX_FOTOS && <ZonaArchivos onArchivos={subir} />}
          <p className="caption">Hasta {MAX_FOTOS} fotos. La primera es la principal: es la que se ve en el catálogo.</p>
        </section>

        <Campo etiqueta="Nombre del producto" value={d.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={200} placeholder="Aceite de sacha inchi extra virgen" />

        <Chips
          etiqueta="Línea de producto"
          opciones={catalogos.lineas.map((l) => ({ valor: l.codigo, etiqueta: l.nombre }))}
          seleccion={d.lineas}
          onCambiar={elegirLineas}
          error={errores.lineas}
        />

        <div className="editor__ia" aria-live="polite">
          {ia.estado === 'inicial' || ia.estado === 'aceptada' ? (
            <button type="button" className="editor__ia-boton" onClick={sugerir}>
              <Icono src={iconoIa} tamano={18} />
              {ia.estado === 'aceptada' ? 'Generar otra descripción con IA' : 'Sugerir una descripción con IA (borrador)'}
            </button>
          ) : null}
          {ia.estado === 'cargando' && <p className="body-s">Preparando un borrador…</p>}
          {ia.estado === 'no-disponible' && (
            <Alerta tono="aviso" titulo="La redacción asistida no está disponible" descripcion="Escribe la descripción con tus palabras; la plataforma funciona igual." onCerrar={() => setIa({ estado: 'inicial' })} />
          )}
          {ia.estado === 'error' && <Alerta tono="error" titulo="No pudimos generar el borrador" descripcion={ia.mensaje} onCerrar={() => setIa({ estado: 'inicial' })} />}
          {ia.estado === 'propuesta' && (
            <div className="editor__ia-panel">
              <p className="editor__ia-titulo">
                <Icono src={iconoIa} tamano={18} />
                Borrador asistido por IA · revisa antes de publicar
              </p>
              <p className="body-s editor__ia-texto">{ia.texto}</p>
              <p className="caption">Edítalo con tus palabras; no se publica hasta que lo aceptes.</p>
              <div className="editor__ia-acciones">
                <Boton tipo="secundario" tamano="S" onClick={() => aceptarDescripcion(true)}>
                  Editar
                </Boton>
                <Boton tipo="terciario" tamano="S" onClick={sugerir}>
                  Generar otra
                </Boton>
                <Boton tamano="S" onClick={() => aceptarDescripcion(false)}>
                  Aceptar descripción
                </Boton>
              </div>
            </div>
          )}
        </div>

        <Campo
          id="descripcion-producto"
          etiqueta={d.descripcionAsistida ? 'Descripción (borrador asistido por IA, editable)' : 'Descripción'}
          tipo="area"
          value={d.descripcion}
          onChange={set('descripcion')}
          error={errores.descripcion}
          maxLength={2000}
          placeholder="Qué es, cómo se elabora y para qué sirve."
        />

        <div className="editor__fila">
          <Campo
            etiqueta="Unidad de medida"
            tipo="selector"
            placeholder="Elige una unidad"
            value={d.unidad}
            onChange={set('unidad')}
            error={errores.unidad}
            ayuda="Del catálogo de unidades"
            opciones={catalogos.unidades.map((u) => ({ valor: u.codigo, etiqueta: u.nombre }))}
          />
          <Campo etiqueta="Contenido por unidad" value={d.contenido} onChange={set('contenido')} maxLength={40} placeholder="250 ml" ayuda="Se muestra en la ficha" />
        </div>

        <OpcionTarjeta nombre="modalidad" etiqueta="Modalidad de venta" opciones={MODALIDADES} valor={d.modalidad} onCambiar={set('modalidad')} />

        <div className="editor__precios">
          {minorista && (
            <Campo etiqueta="Precio minorista (S/)" type="number" inputMode="decimal" min="0" step="0.01" value={d.precio} onChange={set('precio')} error={errores.precio} ayuda="Por unidad" />
          )}
          {mayorista && (
            <>
              <Campo etiqueta="Precio mayorista (S/)" type="number" inputMode="decimal" min="0" step="0.01" value={d.precioMayorista} onChange={set('precioMayorista')} error={errores.precioMayorista} ayuda="Por unidad, desde el mínimo" />
              <Campo etiqueta="Cantidad mínima mayorista" type="number" inputMode="numeric" min="2" step="1" value={d.cantidadMinimaMayorista} onChange={set('cantidadMinimaMayorista')} error={errores.cantidadMinimaMayorista} ayuda="Tú la defines" />
            </>
          )}
        </div>

        <p className="editor__nota-precio">
          <Icono src={iconoInfo} tamano={16} />
          {mayorista
            ? `El precio mayorista se aplica solo desde ${d.cantidadMinimaMayorista || 'la cantidad mínima'} und. Debe ser menor que el minorista. No se permiten descuentos ni precios promocionales.`
            : 'No se permiten descuentos ni precios promocionales.'}
        </p>

        <label className="casilla">
          <input type="checkbox" checked={d.esMateriaPrima} onChange={set('esMateriaPrima')} />
          <span>Es materia prima (no un producto terminado)</span>
        </label>

        <div className="editor__botones">
          <Boton tipo="secundario" onClick={() => guardar(false)} disabled={guardando}>
            {publicado ? 'Guardar cambios' : 'Guardar borrador'}
          </Boton>
          <Boton onClick={() => guardar(true)} disabled={guardando}>
            Siguiente: historia
          </Boton>
        </div>
      </div>

      <aside className="editor__vista-previa" aria-label="Vista previa en el catálogo">
        <h2 className="heading-h4">Vista previa en el catálogo</h2>
        <div className="editor__tarjeta-previa" aria-hidden="true" inert>
          <TarjetaProducto
            producto={{
              id: producto?.id ?? 0,
              nombre: d.nombre || 'Nombre del producto',
              modalidad: d.modalidad,
              precio: minorista && d.precio !== '' ? Number(d.precio) : null,
              precioMayorista: mayorista && d.precioMayorista !== '' ? Number(d.precioMayorista) : null,
              contenido: d.contenido || null,
              linea: d.lineas[0] ? { codigo: d.lineas[0] } : null,
              imagen: principalImg?.urlMiniatura ?? null,
              disponible: true,
              tienda: { nombre: tienda?.nombreTienda ?? tienda?.comunidad ?? '', pueblo: tienda?.pueblo, origenVerificado: tienda?.origenVerificado },
            }}
          />
        </div>
        {comision != null && monto > 0 && (
          <div className="editor__recibiras">
            <p className="label-m">Recibirás {precio(monto * (1 - Number(comision) / 100))} por unidad</p>
            <p className="caption">Comisión de la Cámara: {Number(comision)} % (valor de ejemplo, configurable).</p>
          </div>
        )}
      </aside>
    </div>
  )
}
