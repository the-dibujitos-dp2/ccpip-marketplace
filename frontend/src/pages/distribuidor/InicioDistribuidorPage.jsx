import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoCamion from '../../assets/icons/camion.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { listarMisEntregas, listarPedidosDisponibles } from '../../api/distribucion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutDistribuidor from '../../components/layout/LayoutDistribuidor.jsx'
import { diaMesHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import './InicioDistribuidorPage.css'

const EN_CURSO = ['ASIGNADA', 'RETIRADA', 'EN_CAMINO']

/**
 * CCPIP-500: el inicio del distribuidor resume su reparto en vez de un texto generico. Cuantos pedidos puede
 * tomar, cuantos tiene por retirar del punto, cuantos van en camino y cuantos entrego hoy; cada cifra lleva a
 * su pantalla (CCPIP-456). Debajo, la siguiente entrega en curso: la que tomo primero.
 */
export default function InicioDistribuidorPage({ path }) {
  const { t } = useTranslation()
  const consultar = useCallback(
    () => Promise.all([listarPedidosDisponibles({ limite: 1 }), listarMisEntregas()])
      .then(([disponibles, entregas]) => ({ disponibles: disponibles.total, entregas })),
    [],
  )
  const { datos, error, recargar } = useConsulta(consultar)
  const titulo = t('distribuidor.inicio.titulo')

  let contenido
  if (error) {
    contenido = (
      <EstadoVacio titulo={t('distribuidor.inicio.error')} descripcion={mensajeError(error, 'comun.errorConexion')}>
        <Boton tamano="M" onClick={recargar}>{t('distribuidor.inicio.reintentar')}</Boton>
      </EstadoVacio>
    )
  } else if (!datos) {
    contenido = <Cargando texto={t('distribuidor.inicio.cargando')} />
  } else {
    const contar = (...estados) => datos.entregas.filter((e) => estados.includes(e.estado)).length
    const porRetirar = contar('ASIGNADA')
    const enCamino = contar('RETIRADA', 'EN_CAMINO')
    const entregadas = contar('ENTREGADA')
    const siguiente = datos.entregas.find((e) => EN_CURSO.includes(e.estado))
    contenido = (
      <>
        <div className="inicio-distribuidor__kpis">
          <TarjetaKpi titulo={t('distribuidor.inicio.kpi.disponibles')} valor={String(datos.disponibles)}
            detalle={t('distribuidor.inicio.kpi.disponiblesDetalle')} tono="neutro" icono={iconoCaja}
            destino={RUTAS.distribuidorPedidos} accion={t('distribuidor.inicio.kpi.verDisponibles')} />
          <TarjetaKpi titulo={t('distribuidor.inicio.kpi.porRetirar')} valor={String(porRetirar)}
            detalle={t('distribuidor.inicio.kpi.porRetirarDetalle')} tono={porRetirar > 0 ? 'aviso' : 'neutro'} icono={iconoReloj}
            destino={RUTAS.distribuidorEntregas} accion={t('distribuidor.inicio.kpi.verEntregas')} />
          <TarjetaKpi titulo={t('distribuidor.inicio.kpi.enCamino')} valor={String(enCamino)}
            detalle={t('distribuidor.inicio.kpi.enCaminoDetalle')} tono="neutro" icono={iconoCamion}
            destino={RUTAS.distribuidorEntregas} accion={t('distribuidor.inicio.kpi.verEntregas')} />
          <TarjetaKpi titulo={t('distribuidor.inicio.kpi.entregadas')} valor={String(entregadas)}
            detalle={t('distribuidor.inicio.kpi.entregadasDetalle')} icono={iconoCheck} />
        </div>
        <section className="inicio-distribuidor__siguiente" aria-labelledby="inicio-siguiente">
          <h2 id="inicio-siguiente" className="heading-h3">{t('distribuidor.inicio.siguiente')}</h2>
          {siguiente ? (
            <div className="inicio-distribuidor__tarjeta">
              <p className="heading-h3">{siguiente.numero}</p>
              <p className="body-m">
                {t('distribuidor.inicio.ruta', {
                  origen: siguiente.origen.nombre,
                  destino: [siguiente.destino.distrito, siguiente.destino.provincia].filter(Boolean).join(', '),
                })}
              </p>
              <p className="caption">
                {t(`distribuidor.inicio.estado.${siguiente.estado}`)}
                {' · '}
                {t('distribuidor.inicio.tomadaEn', { fecha: diaMesHora(siguiente.asignadaEn) })}
              </p>
              <Boton tamano="S" to={RUTAS.distribuidorEntregas}>{t('distribuidor.inicio.continuar')}</Boton>
            </div>
          ) : (
            <div className="inicio-distribuidor__tarjeta">
              <p className="body-m">{t('distribuidor.inicio.sinEnCurso')}</p>
              {datos.disponibles > 0 && (
                <Boton tamano="S" to={RUTAS.distribuidorPedidos}>{t('distribuidor.inicio.tomarPedidos')}</Boton>
              )}
            </div>
          )}
        </section>
      </>
    )
  }

  return (
    <LayoutDistribuidor path={path} titulo={titulo}>
      <div className="inicio-distribuidor">{contenido}</div>
    </LayoutDistribuidor>
  )
}
