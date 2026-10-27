import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { calificacionesDelPedido, calificarLinea } from '../../api/valoracion.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import DialogoDenuncia from '../../components/DialogoDenuncia.jsx'
import Estrellas, { SelectorEstrellas } from '../../components/Estrellas.jsx'
import { diaMes } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import './Valoraciones.css'

/**
 * CCPIP-81 (RF-88): en un pedido recibido, el comprador califica cada producto con 1 a 5 estrellas,
 * una sola vez (compra verificada; el servidor lo exige). CCPIP-83: y puede denunciar lo que recibio.
 */
export default function CalificarPedido({ pedidoId }) {
  const { t } = useTranslation()
  const [datos, setDatos] = useState(null)
  const [denunciar, setDenunciar] = useState(null)
  const [denunciada, setDenunciada] = useState(false)

  useEffect(() => {
    let vigente = true
    calificacionesDelPedido(pedidoId).then((d) => vigente && setDatos(d)).catch(() => {})
    return () => {
      vigente = false
    }
  }, [pedidoId])

  if (!datos?.calificable) return null
  return (
    <section className="pedido__tarjeta calificar" aria-labelledby="pedido-calificar">
      <h2 id="pedido-calificar" className="pedido__tarjeta-titulo">{t('calificaciones.calificaTusProductos')}</h2>
      <p className="body-s">
        {datos.pendientes > 0 ? t('calificaciones.pendientes', { count: datos.pendientes }) : t('calificaciones.todoCalificado')}
      </p>
      {denunciada && <Alerta tono="exito" titulo={t('denuncia.enviada')} descripcion={t('denuncia.enviadaTexto')} onCerrar={() => setDenunciada(false)} />}
      {datos.lineas.map((l) => (
        <LineaCalificar key={l.itemId} pedidoId={pedidoId} linea={l} onCalificada={setDatos}
          onDenunciar={() => setDenunciar({ ids: { productoId: l.productoId }, nombre: l.nombre })} />
      ))}
      <DialogoDenuncia abierto={denunciar !== null} objetivo={denunciar} onCerrar={() => setDenunciar(null)}
        onEnviada={() => {
          setDenunciar(null)
          setDenunciada(true)
        }} />
    </section>
  )
}

function LineaCalificar({ pedidoId, linea, onCalificada, onDenunciar }) {
  const { t } = useTranslation()
  const hecha = linea.calificacion

  return (
    <article className="calificar__linea" aria-label={linea.nombre}>
      <div className="calificar__cabecera">
        <span>
          <span className="label-m">{linea.nombre}</span>
          <span className="caption"> · {t('carrito.deTienda', { nombre: linea.tienda })}</span>
        </span>
        <Boton tipo="terciario" tamano="S" onClick={onDenunciar} aria-label={t('denuncia.denunciarDe', { nombre: linea.nombre })}>
          {t('denuncia.denunciar')}
        </Boton>
      </div>
      {hecha ? (
        <div>
          <Estrellas valor={hecha.estrellas} />
          {hecha.comentario && <p className="body-s">{hecha.comentario}</p>}
          <span className="caption">{t('calificaciones.calificadoEl', { fecha: diaMes(hecha.calificadoEn) })}</span>
        </div>
      ) : (
        <FormularioCalificacion pedidoId={pedidoId} itemId={linea.itemId} onCalificada={onCalificada} />
      )}
    </article>
  )
}

/**
 * Estrellas y comentario de una linea recibida. onCalificada recibe las calificaciones del pedido que
 * devuelve el servidor. CCPIP-412: tambien lo usa la ficha del producto.
 */
export function FormularioCalificacion({ pedidoId, itemId, onCalificada }) {
  const { t } = useTranslation()
  const [estrellas, setEstrellas] = useState(null)
  const [comentario, setComentario] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)

  async function enviar(e) {
    e.preventDefault()
    if (!estrellas) {
      setError(t('calificaciones.eligeEstrellas'))
      return
    }
    setEnviando(true)
    setError(null)
    try {
      onCalificada(await calificarLinea(pedidoId, { itemId, estrellas, comentario }))
    } catch (err) {
      setError(mensajeError(err, 'calificaciones.errorEnvio'))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form className="calificar" onSubmit={enviar} noValidate>
      <SelectorEstrellas valor={estrellas} onCambiar={setEstrellas} etiqueta={t('calificaciones.tuCalificacion')} disabled={enviando} />
      <Campo tipo="area" etiqueta={t('calificaciones.comentario')} value={comentario} maxLength={500} rows={2}
        onChange={(e) => setComentario(e.target.value)} ayuda={t('calificaciones.comentarioAyuda')} />
      {error && <Alerta tono="error" titulo={error} />}
      <div className="calificar__acciones">
        <Boton type="submit" tamano="S" cargando={enviando}>
          {enviando ? t('calificaciones.enviando') : t('calificaciones.enviar')}
        </Boton>
      </div>
    </form>
  )
}
