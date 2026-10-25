import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { resumenCalificaciones } from '../../api/valoracion.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoDenuncia from '../../components/DialogoDenuncia.jsx'
import Estrellas from '../../components/Estrellas.jsx'
import { fecha, unidades } from '../../formato.js'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
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
 */
export default function CalificacionesProducto({ producto }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const [resumen, setResumen] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [denunciando, setDenunciando] = useState(false)
  const [enviada, setEnviada] = useState(false)

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
  }, [producto.id])

  const comprador = user?.role === 'CONSUMIDOR'
  return (
    <section className="seccion valoraciones" aria-labelledby="titulo-calificaciones">
      <h2 id="titulo-calificaciones" className="seccion__titulo">{t('calificaciones.titulo')}</h2>
      {/* CCPIP-413: mientras llega el resumen, el indicador en lugar de una seccion vacia */}
      {cargando && <Cargando />}
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
