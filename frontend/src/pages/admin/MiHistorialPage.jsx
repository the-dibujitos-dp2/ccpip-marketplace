import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCandado from '../../assets/icons/candado.svg'
import { miHistorial } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import '../productor/productor.css'
import './admin.css'
import './MiHistorialPage.css'

const TIPOS = ['AUTORIZACIONES', 'MODERACION', 'PRODUCTORES', 'DENUNCIAS', 'CUENTAS']

/** Color de la insignia de cada accion; lo que no esta aqui va en neutro. */
const TONO = {
  AUTORIZACION_APROBADA: 'exito', HABILITAR_TIENDA: 'exito', RESTITUIR_VENTA: 'exito', DESBLOQUEAR_VENTA: 'exito',
  DENUNCIA_RESUELTA: 'exito', CUENTA_REACTIVAR: 'exito', PUBLICACION_APROBADA: 'exito', VIDEO_PUBLICADO: 'exito',
  AUTORIZACION_RECHAZADA: 'error', AUTORIZACION_REVOCADA: 'error', REVOCAR_VENTA: 'error', BLOQUEAR_VENTA: 'error',
  CUENTA_SUSPENDER: 'error', PUBLICACION_RECHAZADA: 'error', VIDEO_RECHAZADO: 'error',
  RENOVACION_SOLICITADA: 'aviso', DENUNCIA_EN_REVISION: 'aviso',
}

/** Pantalla a la que lleva "Sobre". */
const DESTINO = {
  AUTORIZACION: (id) => `${RUTAS.adminAutorizaciones}/${id}`,
  MODERACION: (id) => `${RUTAS.adminModeracion}/${id}`,
  VIDEO: (id) => `${RUTAS.adminModeracionVideos}/${id}`,
  PRODUCTOR: (id) => `${RUTAS.adminProductores}/${id}`,
  DENUNCIAS: (id) => `${RUTAS.adminDenuncias}/productores/${id}`,
  CUENTA: (id) => `${RUTAS.adminCuentas}/${id}`,
}

/**
 * S-05 Mi historial (CCPIP-126, RF-85): las acciones de gobernanza del sub administrador de la sesion,
 * con fecha, motivo y objeto. Es solo lectura: el registro no es editable por ningun rol.
 */
export default function MiHistorialPage({ path }) {
  const { t } = useTranslation()
  const [filtro, setFiltro] = useState({ desde: '', hasta: '', tipo: '' })
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)

  const consultar = useCallback(async (valores) => {
    setError(null)
    try {
      const respuesta = await miHistorial(valores)
      setDatos(respuesta)
      setFiltro({ desde: respuesta.desde, hasta: respuesta.hasta, tipo: respuesta.tipo ?? '' })
    } catch (e) {
      setError(e)
    }
  }, [])

  useEffect(() => {
    void consultar()
  }, [consultar])

  const cambiar = (campo) => (e) => {
    const nuevo = { ...filtro, [campo]: e.target.value }
    setFiltro(nuevo)
    if (nuevo.desde && nuevo.hasta) void consultar(nuevo)
  }

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.miHistorial')} titulo={t('miHistorial.titulo')}>
      <p className="body-m">{t('miHistorial.intro')}</p>
      <div className="mi-historial__filtros">
        <Campo etiqueta={t('miHistorial.desde')} type="date" value={filtro.desde} max={filtro.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('miHistorial.hasta')} type="date" value={filtro.hasta} min={filtro.desde || undefined} onChange={cambiar('hasta')} />
        <Campo
          etiqueta={t('miHistorial.tipo')}
          tipo="selector"
          placeholder={t('miHistorial.todas')}
          value={filtro.tipo}
          onChange={cambiar('tipo')}
          opciones={TIPOS.map((tipo) => ({ valor: tipo, etiqueta: t(`miHistorial.tipos.${tipo}`) }))}
        />
      </div>
      {error && <Alerta tono="error" titulo={mensajeError(error, 'miHistorial.error')} />}
      {!datos && !error && <Cargando />}
      {datos && datos.acciones.length === 0 && <EstadoVacio titulo={t('miHistorial.vacio')} />}
      {datos?.acciones.length > 0 && (
        <div className="tabla mi-historial__tabla" role="table" aria-label={t('miHistorial.titulo')}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda mi-historial__fecha" role="columnheader">{t('miHistorial.col.fecha')}</span>
            <span className="tabla__celda mi-historial__accion" role="columnheader">{t('miHistorial.col.accion')}</span>
            <span className="tabla__celda mi-historial__sobre" role="columnheader">{t('miHistorial.col.sobre')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('miHistorial.col.motivo')}</span>
          </div>
          {datos.acciones.map((a) => (
            <div key={a.clave} className="tabla__fila" role="row">
              <span className="tabla__celda mi-historial__fecha label-m" role="cell">
                <time dateTime={a.fecha}>{fechaHora(a.fecha)}</time>
              </span>
              <span className="tabla__celda mi-historial__accion" role="cell">
                <Badge tono={TONO[a.accion] ?? 'neutro'}>{t(`miHistorial.accion.${a.accion}`, { defaultValue: a.accion })}</Badge>
              </span>
              <span className="tabla__celda mi-historial__sobre" role="cell">
                <Enlace to={DESTINO[a.objeto](a.objetoId)}>{a.sobre}</Enlace>
                {a.detalle && (
                  <span className="caption">{a.tipo === 'DENUNCIAS' ? t('miHistorial.denuncia', { numero: a.detalle }) : a.detalle}</span>
                )}
              </span>
              <span className="tabla__celda tabla__celda--flex body-s" role="cell">{a.motivo ?? '—'}</span>
            </div>
          ))}
        </div>
      )}
      <p className="caption mi-historial__nota">
        <Icono src={iconoCandado} tamano={16} />
        {t('miHistorial.inalterable')}
      </p>
    </LayoutAdmin>
  )
}
