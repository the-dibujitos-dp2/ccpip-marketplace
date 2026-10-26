import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCamara from '../../assets/icons/camara.svg'
import iconoCerrar from '../../assets/icons/cerrar.svg'
import iconoEditar from '../../assets/icons/editar.svg'
import iconoIa from '../../assets/icons/ia.svg'
import iconoInfo from '../../assets/icons/info-pequeno.svg'
import { obtenerCatalogos } from '../../api/catalogo.js'
import {
  aceptarBorradorDescripcion, aceptarMejora, eliminarImagen, marcarPrincipal, mejorarImagen, proponerDescripcion, rechazarBorrador, rechazarMejora, reemplazarImagen, subirImagen,
} from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Chips from '../../components/Chips.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Icono from '../../components/Icono.jsx'
import OpcionTarjeta from '../../components/OpcionTarjeta.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import ZonaArchivos from '../../components/ZonaArchivos.jsx'
import { ApiError } from '../../api/http.js'
import { precio } from '../../formato.js'
import { mensajeError, traducirFaltante } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import { AvisosFotos, ChipRevisar, ComparacionMejora, useValidaciones } from './IaFotos.jsx'
import './Editor.css'

export const MAX_FOTOS = 6
const MAX_BYTES_FOTO = 10 * 1024 * 1024

const MODALIDADES = ['MINORISTA', 'MAYORISTA', 'AMBAS']

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
  const t = (clave) => i18n.t(`productor.datos.validacion.${clave}`)
  const e = {}
  if (!d.nombre.trim()) e.nombre = t('nombre')
  const minorista = d.modalidad !== 'MAYORISTA'
  const mayorista = d.modalidad !== 'MINORISTA'
  if (d.precio !== '' && !(Number(d.precio) >= 0.1)) e.precio = t('precioMinimo')
  if (d.precioMayorista !== '' && !(Number(d.precioMayorista) >= 0.1)) e.precioMayorista = t('precioMinimo')
  if (minorista && mayorista && d.precio !== '' && d.precioMayorista !== '' && Number(d.precioMayorista) >= Number(d.precio)) {
    e.precioMayorista = t('mayoristaMenor')
  }
  if (mayorista && d.precioMayorista !== '' && !(Number(d.cantidadMinimaMayorista) >= 2)) e.cantidadMinimaMayorista = t('cantidadMinima')
  if (paraPublicar) {
    if (!d.lineas.length) e.lineas = t('lineas')
    if (!d.unidad) e.unidad = t('unidad')
    if (minorista && d.precio === '') e.precio = t('precioMinorista')
    if (mayorista && d.precioMayorista === '') e.precioMayorista = t('precioMayorista')
  }
  return e
}

/** PW-04 · paso 1 "Datos": todo lo que el comprador ve del producto (CCPIP-40, 45, 132, 44). */
export default function PasoDatos({ producto, tienda, asegurarProducto, onGuardar, onSiguiente, onImagenes }) {
  const { t } = useTranslation()
  const [catalogos, setCatalogos] = useState({ lineas: [], unidades: [], comisionPorcentaje: null })
  const [d, setD] = useState(() => datosIniciales(producto))
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  // 'datos' o 'siguiente': cual de los dos botones guarda (CCPIP-413: el aro va solo en el pulsado)
  const [guardando, setGuardando] = useState(null)
  const [subidas, setSubidas] = useState([])
  const [aRetirar, setARetirar] = useState(null)
  const [retirando, setRetirando] = useState(false)
  const [reemplazando, setReemplazando] = useState(null)
  const [ia, setIa] = useState({ estado: 'inicial' })
  const [mejorando, setMejorando] = useState(null)
  const [comparacion, setComparacion] = useState(null)
  const [decidiendo, setDecidiendo] = useState(false)

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
  const { porFoto, validar } = useValidaciones(producto?.id, imagenes)
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
      setAlerta({ tono: 'error', titulo: t('productor.datos.revisa'), descripcion: Object.values(e).join(' · ') })
      return
    }
    setGuardando(siguiente ? 'siguiente' : 'datos')
    setAlerta(null)
    try {
      await onGuardar(aCuerpo(d))
      if (siguiente) onSiguiente()
      else if (publicado) setAlerta({ tono: 'exito', titulo: t('productor.datos.cambiosGuardados'), descripcion: t('productor.datos.cambiosGuardadosTexto') })
      else setAlerta({ tono: 'exito', titulo: t('productor.datos.borradorGuardado'), descripcion: t('productor.datos.borradorGuardadoTexto') })
    } catch (err) {
      // Los mensajes por campo llegan en espanol: en ingles se marca el campo con el mensaje del code
      const campos = err.body?.campos ?? {}
      setErrores(i18n.resolvedLanguage === 'es' ? campos : Object.fromEntries(Object.keys(campos).map((c) => [c, mensajeError(err)])))
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.datos.errorGuardar'), descripcion: err.body?.detalles?.map(traducirFaltante).join(' · ') })
    } finally {
      setGuardando(null)
    }
  }

  // ---------- Fotos (CCPIP-44): una peticion por archivo, con reintento ----------

  async function subir(archivos) {
    let id
    try {
      id = await asegurarProducto(aCuerpo(d))
    } catch (err) {
      setErrores({ nombre: t('productor.editor.nombreAntesFotos') })
      setAlerta({ tono: 'error', titulo: err instanceof ApiError ? mensajeError(err) : (err.message ?? t('productor.datos.guardaAntesFotos')) })
      return
    }
    const libres = MAX_FOTOS - imagenes.length - subidas.filter((s) => s.estado !== 'error').length
    const tomados = archivos.slice(0, Math.max(0, libres))
    if (archivos.length > tomados.length) {
      setAlerta({ tono: 'aviso', titulo: t('productor.datos.maxFotos', { max: MAX_FOTOS }), descripcion: t('productor.datos.maxFotosTexto') })
    }
    for (const archivo of tomados) {
      await subirUno(id, archivo)
    }
    onImagenes(id)
  }

  async function subirUno(id, archivo, clave = `${archivo.name}-${archivo.size}-${Date.now()}`) {
    if (archivo.size > MAX_BYTES_FOTO) {
      setSubidas((s) => [...s.filter((x) => x.clave !== clave), { clave, archivo, estado: 'error', mensaje: t('productor.datos.pesaMucho') }])
      return
    }
    setSubidas((s) => [...s.filter((x) => x.clave !== clave), { clave, archivo, estado: 'subiendo' }])
    try {
      const nueva = await subirImagen(archivo, { tipo: 'PRODUCTO', productoId: id })
      setSubidas((s) => s.filter((x) => x.clave !== clave))
      // CCPIP-144: cada foto nueva se valida contra la linea; el resultado solo recomienda
      if (nueva?.id) void validar(nueva.id, { conLinea: d.lineas.length > 0 })
    } catch (err) {
      setSubidas((s) => s.map((x) => (x.clave === clave ? { ...x, estado: 'error', mensaje: mensajeError(err, 'productor.datos.seCorto') } : x)))
    }
  }

  async function reintentar(s) {
    const id = await asegurarProducto(aCuerpo(d))
    await subirUno(id, s.archivo, s.clave)
    onImagenes(id)
  }

  /** Retirar, reemplazar o hacer principal una foto: si el servidor falla, se avisa y la lista se recarga. */
  async function accionFoto(accion, mensaje) {
    let resultado
    try {
      resultado = await accion()
    } catch (err) {
      setAlerta({ tono: 'error', titulo: mensajeError(err, mensaje) })
    }
    onImagenes(producto.id)
    return resultado
  }

  // CCPIP-389: retirar pide confirmacion; la foto sale de la ficha pero se conserva
  async function retirar() {
    setRetirando(true)
    await accionFoto(() => eliminarImagen(aRetirar.id), 'productor.datos.errorQuitarFoto')
    setRetirando(false)
    setARetirar(null)
  }

  // CCPIP-388: la nueva toma el lugar de la anterior, incluso si era la principal
  async function reemplazar(imagen, archivo) {
    if (!archivo) return
    if (archivo.size > MAX_BYTES_FOTO) {
      setAlerta({ tono: 'error', titulo: t('productor.datos.pesaMucho') })
      return
    }
    setReemplazando(imagen.id)
    const nueva = await accionFoto(() => reemplazarImagen(imagen.id, archivo), 'productor.datos.errorReemplazar')
    setReemplazando(null)
    // CCPIP-364: la que entra desde el aviso de la IA se vuelve a validar
    if (nueva?.id) void validar(nueva.id, { conLinea: d.lineas.length > 0 })
  }

  // ---------- Mejora opcional con IA (CCPIP-176, 272, 281, 282) ----------

  async function mejorar(imagen) {
    setMejorando(imagen.id)
    try {
      setComparacion(await mejorarImagen(imagen.id))
    } catch (err) {
      setAlerta(err.code === 'IA_NO_DISPONIBLE'
        ? { tono: 'aviso', titulo: t('productor.datos.mejora.noDisponible'), descripcion: t('productor.datos.mejora.noDisponibleTexto') }
        : { tono: 'error', titulo: mensajeError(err, 'productor.datos.mejora.errorPedir'), descripcion: t('productor.datos.mejora.noDisponibleTexto') })
    } finally {
      setMejorando(null)
    }
  }

  // Solo una eleccion expresa cambia algo: usar la mejorada la pone en el lugar de la original
  async function decidirMejora(usar) {
    setDecidiendo(true)
    try {
      if (usar) {
        const nueva = await aceptarMejora(comparacion.imagenId)
        setAlerta({ tono: 'exito', titulo: t('productor.datos.mejora.aceptada'), descripcion: t('productor.datos.mejora.aceptadaTexto') })
        if (nueva?.id) void validar(nueva.id, { conLinea: d.lineas.length > 0 })
      } else {
        await rechazarMejora(comparacion.imagenId)
        setAlerta({ tono: 'exito', titulo: t('productor.datos.mejora.conservada') })
      }
    } catch (err) {
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.datos.mejora.errorDecidir') })
    } finally {
      setDecidiendo(false)
      setComparacion(null)
      onImagenes(producto.id)
    }
  }

  const nombreLinea = (codigo) => (codigo ? (catalogos.lineas.find((l) => l.codigo === codigo)?.nombre ?? codigo) : null)

  const principal = (imagen) => accionFoto(() => marcarPrincipal(imagen.id), 'productor.datos.errorPrincipal')

  // ---------- Descripcion asistida (CCPIP-48, CCPIP-49, RNF-29) ----------

  async function sugerir() {
    if (!d.nombre.trim()) {
      setErrores({ ...errores, nombre: t('productor.datos.nombreParaIa') })
      return
    }
    setIa({ estado: 'cargando' })
    let productoId
    try {
      // CCPIP-49: el servidor guarda el borrador solo de un producto que existe; uno nuevo nace como borrador
      productoId = Number(await asegurarProducto(aCuerpo(d)))
    } catch (err) {
      setIa({ estado: 'inicial' })
      setAlerta({ tono: 'error', titulo: err instanceof ApiError ? mensajeError(err) : t('productor.datos.ia.guardaAntes') })
      return
    }
    try {
      const propuesta = await proponerDescripcion({ productoId, nombre: d.nombre.trim(), lineas: d.lineas, contenido: d.contenido || null })
      setIa({ estado: 'propuesta', texto: propuesta.texto, productoId })
    } catch (err) {
      setIa({ estado: err.code === 'IA_NO_DISPONIBLE' ? 'no-disponible' : 'error', mensaje: mensajeError(err) })
    }
  }

  // CCPIP-280: descartar el borrador lo registra. Mientras responde no se puede decidir otra vez
  async function descartarDescripcion() {
    setIa({ ...ia, ocupado: 'descartar' })
    try {
      await rechazarBorrador(ia.productoId, 'DESCRIPCION')
    } catch (err) {
      // 409: ya no estaba pendiente (se decidio en otra pestana); igual se descarta
      if (err.status !== 409) {
        setIa({ ...ia, ocupado: null })
        setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.datos.ia.errorDescartar') })
        return
      }
    }
    setIa({ estado: 'descartada' })
  }

  // CCPIP-49: aceptarlo guarda la version sugerida, con quien y cuando; la final es la que el productor guarde
  async function aceptarDescripcion(editar) {
    // CCPIP-413: 'editar' o 'aceptar', para que el aro vaya solo en el boton pulsado
    setIa({ ...ia, ocupado: editar ? 'editar' : 'aceptar' })
    try {
      await aceptarBorradorDescripcion(ia.productoId)
    } catch (err) {
      // 409: ya no estaba pendiente; mejor pedir otro que usar un texto cuya aceptacion no quedo registrada
      setIa(err.status === 409 ? { estado: 'inicial' } : { ...ia, ocupado: null })
      setAlerta({ tono: 'error', titulo: err.status === 409 ? t('productor.datos.ia.yaNoPendiente') : mensajeError(err, 'productor.datos.ia.errorAceptar') })
      return
    }
    setD((actual) => ({ ...actual, descripcion: ia.texto, descripcionAsistida: true }))
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
          <h2 id="titulo-fotos" className="heading-h4">{t('productor.datos.fotos')}</h2>
          <ul className="editor__miniaturas">
            {imagenes.map((img) => (
              <li key={img.id} className={`editor__miniatura${img.principal ? ' editor__miniatura--principal' : ''}`}>
                <img src={img.urlMiniatura} alt={t('productor.datos.fotoProducto')} />
                {porFoto[img.id]?.estado === 'SOSPECHOSA' && <ChipRevisar imagenId={img.id} />}
                {img.principal ? (
                  <span className="editor__principal">{t('productor.datos.principal')}</span>
                ) : (
                  <button type="button" className="editor__accion-foto editor__accion-foto--principal" onClick={() => void principal(img)}>
                    {t('productor.datos.hacerPrincipal')}
                  </button>
                )}
                <label className={`editor__reemplazar${reemplazando === img.id ? ' editor__reemplazar--ocupado' : ''}`} title={t('productor.datos.reemplazarFoto')}>
                  <Icono src={iconoEditar} tamano={14} />
                  <span className="visually-hidden">{t('productor.datos.reemplazarFoto')}</span>
                  <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" disabled={reemplazando !== null}
                    onChange={(e) => { void reemplazar(img, e.target.files?.[0]); e.target.value = '' }} />
                </label>
                <button type="button" className="editor__quitar" aria-label={t('productor.datos.quitarFoto')} onClick={() => setARetirar(img)}>
                  <Icono src={iconoCerrar} tamano={14} />
                </button>
              </li>
            ))}
            {subidas.map((s) => (
              <li key={s.clave} className={`editor__miniatura editor__miniatura--${s.estado}`}>
                <span className="caption">{s.estado === 'subiendo' ? t('productor.datos.subiendo') : s.mensaje}</span>
                {s.estado === 'error' && (
                  <button type="button" className="editor__accion-foto" onClick={() => void reintentar(s)}>
                    {t('carrito.reintentar')}
                  </button>
                )}
              </li>
            ))}
            {imagenes.length + subidas.length < MAX_FOTOS && (
              <li className="editor__miniatura editor__miniatura--agregar">
                <label>
                  <Icono src={iconoCamara} tamano={20} />
                  {t('productor.datos.agregar')}
                  <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(e) => { void subir(Array.from(e.target.files ?? [])); e.target.value = '' }} />
                </label>
              </li>
            )}
          </ul>
          <AvisosFotos
            imagenes={imagenes}
            porFoto={porFoto}
            nombreLinea={nombreLinea}
            lineaActual={d.lineas[0]}
            onValidar={validar}
            onReemplazar={reemplazar}
            onMejorar={mejorar}
            reemplazando={reemplazando !== null}
            mejorando={mejorando}
          />
          {imagenes.length < MAX_FOTOS && <ZonaArchivos onArchivos={subir} />}
          <p className="caption">{t('productor.datos.fotosAyuda', { max: MAX_FOTOS })}</p>
          <Dialogo
            abierto={aRetirar !== null}
            titulo={t('productor.datos.retirarTitulo')}
            confirmar={t('productor.datos.retirarConfirmar')}
            tipoConfirmar="destructivo"
            ocupado={retirando}
            onConfirmar={() => void retirar()}
            onCancelar={() => setARetirar(null)}
          >
            <p>{t(aRetirar?.principal ? 'productor.datos.retirarTextoPrincipal' : 'productor.datos.retirarTexto')}</p>
          </Dialogo>
          <ComparacionMejora
            comparacion={comparacion}
            ocupado={decidiendo}
            onUsar={() => void decidirMejora(true)}
            onConservar={() => void decidirMejora(false)}
            onCerrar={() => setComparacion(null)}
          />
        </section>

        <Campo etiqueta={t('productor.datos.nombre')} value={d.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={200} placeholder={t('productor.datos.nombreEjemplo')} />

        <Chips
          etiqueta={t('buscar.linea')}
          opciones={catalogos.lineas.map((l) => ({ valor: l.codigo, etiqueta: l.nombre }))}
          seleccion={d.lineas}
          onCambiar={elegirLineas}
          error={errores.lineas}
        />

        <div className="editor__ia" aria-live="polite">
          {['inicial', 'aceptada', 'descartada'].includes(ia.estado) ? (
            <button type="button" className="editor__ia-boton" onClick={sugerir}>
              <Icono src={iconoIa} tamano={18} />
              {ia.estado === 'aceptada' ? t('productor.datos.ia.otra') : t('productor.datos.ia.sugerir')}
            </button>
          ) : null}
          {ia.estado === 'descartada' && <p className="caption">{t('productor.datos.ia.descartada')}</p>}
          {ia.estado === 'cargando' && <p className="body-s">{t('productor.datos.ia.preparando')}</p>}
          {ia.estado === 'no-disponible' && (
            <Alerta tono="aviso" titulo={t('productor.datos.ia.noDisponible')} descripcion={t('productor.datos.ia.noDisponibleTexto')} onCerrar={() => setIa({ estado: 'inicial' })} />
          )}
          {ia.estado === 'error' && <Alerta tono="error" titulo={t('productor.datos.ia.error')} descripcion={ia.mensaje} onCerrar={() => setIa({ estado: 'inicial' })} />}
          {ia.estado === 'propuesta' && (
            <div className="editor__ia-panel">
              <p className="editor__ia-titulo">
                <Icono src={iconoIa} tamano={18} />
                {t('productor.datos.ia.borrador')}
              </p>
              <p className="body-s editor__ia-texto">{ia.texto}</p>
              <p className="caption">{t('productor.datos.ia.editalo')}</p>
              <div className="editor__ia-acciones">
                <Boton tipo="secundario" tamano="S" disabled={Boolean(ia.ocupado)} cargando={ia.ocupado === 'editar'} onClick={() => void aceptarDescripcion(true)}>
                  {t('productor.editor.editar')}
                </Boton>
                <Boton tipo="terciario" tamano="S" disabled={Boolean(ia.ocupado)} onClick={sugerir}>
                  {t('productor.datos.ia.generarOtra')}
                </Boton>
                <Boton tipo="terciario" tamano="S" disabled={Boolean(ia.ocupado)} cargando={ia.ocupado === 'descartar'} onClick={() => void descartarDescripcion()}>
                  {t('productor.datos.ia.descartar')}
                </Boton>
                <Boton tamano="S" disabled={Boolean(ia.ocupado)} cargando={ia.ocupado === 'aceptar'} onClick={() => void aceptarDescripcion(false)}>
                  {t('productor.datos.ia.aceptar')}
                </Boton>
              </div>
            </div>
          )}
        </div>

        <Campo
          id="descripcion-producto"
          etiqueta={d.descripcionAsistida ? t('productor.datos.descripcionAsistida') : t('productor.datos.descripcion')}
          tipo="area"
          value={d.descripcion}
          onChange={set('descripcion')}
          error={errores.descripcion}
          maxLength={2000}
          placeholder={t('productor.datos.descripcionPlaceholder')}
        />

        <div className="editor__fila">
          <Campo
            etiqueta={t('productor.datos.unidad')}
            tipo="selector"
            placeholder={t('productor.datos.eligeUnidad')}
            value={d.unidad}
            onChange={set('unidad')}
            error={errores.unidad}
            ayuda={t('productor.datos.unidadAyuda')}
            opciones={catalogos.unidades.map((u) => ({ valor: u.codigo, etiqueta: u.nombre }))}
          />
          <Campo etiqueta={t('productor.datos.contenido')} value={d.contenido} onChange={set('contenido')} maxLength={40} placeholder="250 ml" ayuda={t('productor.datos.contenidoAyuda')} />
        </div>

        <OpcionTarjeta nombre="modalidad" etiqueta={t('productor.datos.modalidad')} opciones={MODALIDADES.map((m) => ({ valor: m, etiqueta: t(`productor.datos.modalidades.${m}`) }))} valor={d.modalidad} onCambiar={set('modalidad')} />

        <div className="editor__precios">
          {minorista && (
            <Campo etiqueta={t('productor.datos.precioMinorista')} type="number" inputMode="decimal" min="0" step="0.01" value={d.precio} onChange={set('precio')} error={errores.precio} ayuda={t('productor.datos.porUnidad')} />
          )}
          {mayorista && (
            <>
              <Campo etiqueta={t('productor.datos.precioMayorista')} type="number" inputMode="decimal" min="0" step="0.01" value={d.precioMayorista} onChange={set('precioMayorista')} error={errores.precioMayorista} ayuda={t('productor.datos.porUnidadMinimo')} />
              <Campo etiqueta={t('productor.datos.cantidadMinima')} type="number" inputMode="numeric" min="2" step="1" value={d.cantidadMinimaMayorista} onChange={set('cantidadMinimaMayorista')} error={errores.cantidadMinimaMayorista} ayuda={t('productor.datos.tuLaDefines')} />
            </>
          )}
        </div>

        <p className="editor__nota-precio">
          <Icono src={iconoInfo} tamano={16} />
          {mayorista
            ? t('productor.datos.notaMayorista', { minimo: d.cantidadMinimaMayorista || t('productor.datos.laCantidadMinima') })
            : t('productor.datos.sinDescuentos')}
        </p>

        <label className="casilla">
          <input type="checkbox" checked={d.esMateriaPrima} onChange={set('esMateriaPrima')} />
          <span>{t('productor.datos.materiaPrima')}</span>
        </label>

        <div className="editor__botones">
          <Boton tipo="secundario" onClick={() => guardar(false)} disabled={guardando !== null} cargando={guardando === 'datos'}>
            {publicado ? t('productor.datos.guardarCambios') : t('productor.datos.guardarBorrador')}
          </Boton>
          <Boton onClick={() => guardar(true)} disabled={guardando !== null} cargando={guardando === 'siguiente'}>
            {t('productor.datos.siguienteHistoria')}
          </Boton>
        </div>
      </div>

      <aside className="editor__vista-previa" aria-label={t('productor.datos.vistaPrevia')}>
        <h2 className="heading-h4">{t('productor.datos.vistaPrevia')}</h2>
        <div className="editor__tarjeta-previa" aria-hidden="true" inert>
          <TarjetaProducto
            producto={{
              id: producto?.id ?? 0,
              nombre: d.nombre || t('productor.datos.nombre'),
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
            <p className="label-m">{t('productor.datos.recibiras', { monto: precio(monto * (1 - Number(comision) / 100)) })}</p>
            <p className="caption">{t('productor.datos.comision', { porcentaje: Number(comision) })}</p>
          </div>
        )}
      </aside>
    </div>
  )
}
