import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { miCalificacionProducto, resumenCalificaciones } from '../../api/valoracion.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoDenuncia from '../../components/DialogoDenuncia.jsx'
import Estrellas from '../../components/Estrellas.jsx'
import { diaMes, fecha, unidades } from '../../formato.js'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import { FormularioCalificacion } from './CalificarPedido.jsx'
import './Valoraciones.css'

const NIVELES = [
  ['cinco', 5],
  ['cuatro', 4],
  ['tres', 3],
  ['dos', 2],
  ['uno', 1],
]

/**
 * CW-03, seccion "Calificaciones". CCPIP-82 (RF-89): el promedio solo con suficiente evidencia (el
 * umbral lo fija el servidor); sin ella no se muestra un promedio, y menos un cero. Las resenas no
 * llevan el nombre de quien califico. CCPIP-83: el comprador puede denunciar la oferta desde aqui.
 * CCPIP-412: y calificarla, solo si la recibio en un pedido; si no, la opcion explica por que no.
 */
export default function CalificacionesProducto({ producto }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const [resumen, setResumen] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [mia, setMia] = useState(null)
  const [version, setVersion] = useState(0)
  const [denunciando, setDenunciando] = useState(false)
  const [enviada, setEnviada] = useState(false)
  const comprador = user?.role === 'CONSUMIDOR'

  useEffect(() => {
    let vigente = true
    setCargando(true)
    // Sin resumen (error de red), la ficha se ve igual, sin esta seccion
    resumenCalificaciones(producto.id)
      .then((r) => vigente && setResumen(r))
      .catch(() => {})
      .finally(() => vigente && setCargando(false))
    return () => {
      vigente = false
    }
  }, [producto.id, version])

  useEffect(() => {
    if (!comprador) return undefined
    let vigente = true
    // Sin respuesta, la ficha no ofrece calificar: el servidor igual lo exige al enviar
    miCalificacionProducto(producto.id).then((m) => vigente && setMia(m)).catch(() => {})
    return () => {
      vigente = false
    }
  }, [producto.id, comprador, version])

  return (
    <section className="seccion valoraciones" aria-labelledby="titulo-calificaciones">
      <h2 id="titulo-calificaciones" className="seccion__titulo">{t('calificaciones.titulo')}</h2>
      {/* CCPIP-413: mientras llega el primer resumen, el indicador en lugar de una seccion vacia */}
      {cargando && !resumen && <Cargando />}
      {resumen?.suficiente && (
        <div className="valoraciones__resumen">
          <div className="valoraciones__promedio">
            <span className="valoraciones__numero">{unidades(resumen.promedio)}</span>
            <Estrellas valor={resumen.promedio} />
            <span className="caption">{t('calificaciones.basadoEn', { count: resumen.total })}</span>
          </div>
          <ul className="valoraciones__distribucion" aria-label={t('calificaciones.distribucion')}>
            {NIVELES.map(([clave, n]) => (
              <li key={clave}>
                <span>{t('calificaciones.estrellas', { count: n })}</span>
                <span className="valoraciones__barra" aria-hidden="true">
                  <span style={{ width: `${Math.round((resumen.distribucion[clave] / resumen.total) * 100)}%` }} />
                </span>
                <span className="caption">{resumen.distribucion[clave]}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {resumen && !resumen.suficiente && (
        <p className="body-m">
          {/* CCPIP-409: sin resenas, un mensaje amable en lugar de un bloque vacio */}
          {resumen.total === 0
            ? t('calificaciones.sinResenas')
            : t('calificaciones.insuficiente', { count: resumen.total, minimo: resumen.minimo })}
        </p>
      )}
      {resumen?.resenas?.length > 0 && (
        <ul className="valoraciones__resenas" aria-label={t('calificaciones.resenas')}>
          {resumen.resenas.map((r, i) => (
            <li key={`${r.fecha}-${i}`} className="valoraciones__resena">
              <Estrellas valor={r.estrellas} />
              <p className="body-s">{r.comentario}</p>
              <span className="caption">{t('calificaciones.compraVerificada', { fecha: fecha(r.fecha) })}</span>
            </li>
          ))}
        </ul>
      )}
      {comprador && mia && <CalificarDesdeFicha mia={mia} onCalificada={() => setVersion((v) => v + 1)} />}
      <p className="caption">{t('calificaciones.comoSeCalifica')}</p>

      <div className="valoraciones__denuncia">
        {enviada && <Alerta tono="exito" titulo={t('denuncia.enviada')} descripcion={t('denuncia.enviadaTexto')} onCerrar={() => setEnviada(false)} />}
        {comprador && (
          <Boton tipo="terciario" tamano="S" onClick={() => setDenunciando(true)}>{t('denuncia.denunciarProducto')}</Boton>
        )}
        {!user && (
          <Boton tipo="terciario" tamano="S" to={`${RUTAS.ingresar}?volver=${encodeURIComponent(`/productos/${producto.id}`)}`}>
            {t('denuncia.ingresaParaDenunciar')}
          </Boton>
        )}
      </div>
      {comprador && (
        <DialogoDenuncia abierto={denunciando} objetivo={{ ids: { productoId: producto.id }, nombre: producto.nombre }}
          onCerrar={() => setDenunciando(false)}
          onEnviada={() => {
            setDenunciando(false)
            setEnviada(true)
          }} />
      )}
    </section>
  )
}

/**
 * CCPIP-412: con un pedido recibido que incluye el producto, el formulario; si ya lo califico, su
 * calificacion; si no lo recibio, la opcion deshabilitada con el motivo.
 */
function CalificarDesdeFicha({ mia, onCalificada }) {
  const { t } = useTranslation()
  if (mia.estado === 'PUEDE_CALIFICAR') {
    return (
      <div className="valoraciones__calificar" role="group" aria-labelledby="calificar-producto">
        <h3 id="calificar-producto" className="label-m">{t('calificaciones.calificaEsteProducto')}</h3>
        <p className="body-s">{t('calificaciones.loRecibiste', { numero: mia.numeroPedido })}</p>
        <FormularioCalificacion pedidoId={mia.pedidoId} itemId={mia.itemId} onCalificada={onCalificada} />
      </div>
    )
  }
  if (mia.estado === 'YA_CALIFICADO') {
    return (
      <div className="valoraciones__calificar">
        <p className="label-m">{t('calificaciones.yaCalificaste')}</p>
        <Estrellas valor={mia.calificacion.estrellas} />
        {mia.calificacion.comentario && <p className="body-s">{mia.calificacion.comentario}</p>}
        <span className="caption">{t('calificaciones.calificadoEl', { fecha: diaMes(mia.calificacion.calificadoEn) })}</span>
      </div>
    )
  }
  const motivo = mia.estado === 'PEDIDO_EN_CURSO'
    ? t('calificaciones.cuandoRecibas', { numero: mia.numeroPedido })
    : t('calificaciones.soloSiRecibiste')
  return (
    <div className="valoraciones__calificar">
      <Boton tipo="secundario" tamano="S" disabled aria-describedby="calificar-motivo">{t('calificaciones.calificaEsteProducto')}</Boton>
      <p id="calificar-motivo" className="caption">{motivo}</p>
    </div>
  )
}
