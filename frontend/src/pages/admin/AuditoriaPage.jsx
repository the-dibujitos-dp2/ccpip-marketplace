import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCandado from '../../assets/icons/candado.svg'
import { auditoria } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'
import './AuditoriaPage.css'

const ROLES = ['CONSUMIDOR', 'PRODUCTOR', 'ADMIN_PUNTO', 'SUB_ADMIN', 'SUPER_ADMIN', 'DISTRIBUIDOR']
const ENTIDADES = ['AUTORIZACION', 'MODERACION', 'VIDEO', 'PRODUCTOR', 'DENUNCIAS', 'CUENTA', 'PRODUCTO',
  'LINEA_MAESTRA', 'PRODUCTO_MAESTRO', 'PEDIDO']
const RESULTADOS = ['EXITOSA', 'APROBADO', 'RECHAZADO', 'ERROR', 'PENDIENTE', 'ANULADO']
const TONO_RESULTADO = { EXITOSA: 'exito', APROBADO: 'exito', RECHAZADO: 'error', ERROR: 'error', PENDIENTE: 'aviso' }

const VACIO = { desde: '', hasta: '', usuario: '', rol: '', accion: '', entidad: '', resultado: '' }

/**
 * Auditoria de operaciones sensibles (CCPIP-97, RF-84): quien (usuario y rol), que accion, sobre que
 * entidad, cuando y con que resultado. Solo del superadministrador y solo lectura: los registros no se
 * editan ni se borran.
 */
export default function AuditoriaPage({ path }) {
  const { t } = useTranslation()
  const [filtro, setFiltro] = useState(VACIO)
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)

  const consultar = useCallback(async (valores, pagina = 0) => {
    setError(null)
    try {
      const respuesta = await auditoria({ ...valores, pagina })
      setDatos(respuesta)
      setFiltro((actual) => ({ ...actual, desde: respuesta.desde, hasta: respuesta.hasta }))
    } catch (e) {
      setError(e)
    }
  }, [])

  useEffect(() => {
    void consultar(VACIO)
  }, [consultar])

  /** Fechas y selectores consultan al cambiar; el usuario, al enviar el formulario. */
  const cambiar = (campo) => (e) => {
    const nuevo = { ...filtro, [campo]: e.target.value }
    setFiltro(nuevo)
    if (campo !== 'usuario' && nuevo.desde && nuevo.hasta) void consultar(nuevo)
  }

  const buscar = (e) => {
    e.preventDefault()
    void consultar(filtro)
  }

  const nombreAccion = (codigo) =>
    t(`auditoria.accion.${codigo}`, { defaultValue: t(`miHistorial.accion.${codigo}`, { defaultValue: codigo }) })
  const opciones = (lista, prefijo) => lista.map((valor) => ({ valor, etiqueta: t(`${prefijo}.${valor}`) }))

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.auditoria')} titulo={t('auditoria.titulo')}>
      <p className="body-m">{t('auditoria.intro')}</p>
      <form className="auditoria__filtros" onSubmit={buscar} role="search" aria-label={t('auditoria.filtros')}>
        <Campo etiqueta={t('auditoria.desde')} type="date" value={filtro.desde} max={filtro.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('auditoria.hasta')} type="date" value={filtro.hasta} min={filtro.desde || undefined} onChange={cambiar('hasta')} />
        <Campo etiqueta={t('auditoria.usuario')} value={filtro.usuario} maxLength={120}
          placeholder={t('auditoria.usuarioAyuda')} onChange={cambiar('usuario')} />
        <Campo etiqueta={t('auditoria.rol')} tipo="selector" placeholder={t('auditoria.todos')} value={filtro.rol}
          onChange={cambiar('rol')} opciones={opciones(ROLES, 'roles')} />
        <Campo etiqueta={t('auditoria.accionFiltro')} tipo="selector" placeholder={t('auditoria.todas')} value={filtro.accion}
          onChange={cambiar('accion')}
          opciones={(datos?.acciones ?? []).map((valor) => ({ valor, etiqueta: nombreAccion(valor) }))} />
        <Campo etiqueta={t('auditoria.entidadFiltro')} tipo="selector" placeholder={t('auditoria.todas')} value={filtro.entidad}
          onChange={cambiar('entidad')} opciones={opciones(ENTIDADES, 'auditoria.entidad')} />
        <Campo etiqueta={t('auditoria.resultadoFiltro')} tipo="selector" placeholder={t('auditoria.todos')} value={filtro.resultado}
          onChange={cambiar('resultado')} opciones={opciones(RESULTADOS, 'auditoria.resultado')} />
        <div className="auditoria__acciones">
          <Boton type="submit" tipo="secundario">{t('auditoria.buscar')}</Boton>
        </div>
      </form>
      {error && <Alerta tono="error" titulo={mensajeError(error, 'auditoria.error')} />}
      {!datos && !error && <Cargando />}
      {datos && datos.registros.length === 0 && <EstadoVacio titulo={t('auditoria.vacio')} />}
      {datos?.registros.length > 0 && (
        <div className="tabla auditoria__tabla" role="table" aria-label={t('auditoria.titulo')}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda auditoria__fecha" role="columnheader">{t('auditoria.col.fecha')}</span>
            <span className="tabla__celda auditoria__usuario" role="columnheader">{t('auditoria.col.usuario')}</span>
            <span className="tabla__celda auditoria__rol" role="columnheader">{t('auditoria.col.rol')}</span>
            <span className="tabla__celda auditoria__accion" role="columnheader">{t('auditoria.col.accion')}</span>
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('auditoria.col.entidad')}</span>
            <span className="tabla__celda auditoria__resultado" role="columnheader">{t('auditoria.col.resultado')}</span>
          </div>
          {datos.registros.map((r) => (
            <div key={r.clave} className="tabla__fila" role="row">
              <span className="tabla__celda auditoria__fecha label-m" role="cell">
                <time dateTime={r.fecha}>{fechaHora(r.fecha)}</time>
              </span>
              <span className="tabla__celda auditoria__usuario" role="cell">
                {r.usuario ? (
                  <>
                    <span className="body-s">{r.usuario}</span>
                    <span className="caption">{r.correo}</span>
                  </>
                ) : (
                  <span className="body-s">{t('auditoria.sistema')}</span>
                )}
              </span>
              <span className="tabla__celda auditoria__rol body-s" role="cell">{r.rol ? t(`roles.${r.rol}`) : '—'}</span>
              <span className="tabla__celda auditoria__accion body-s" role="cell">{nombreAccion(r.accion)}</span>
              <span className="tabla__celda tabla__celda--flex auditoria__entidad" role="cell">
                <span className="body-s">{t(`auditoria.entidad.${r.entidad}`)}</span>
                {r.detalle && <span className="caption">{r.detalle}</span>}
              </span>
              <span className="tabla__celda auditoria__resultado" role="cell">
                <Badge tono={TONO_RESULTADO[r.resultado] ?? 'neutro'}>{t(`auditoria.resultado.${r.resultado}`)}</Badge>
              </span>
            </div>
          ))}
        </div>
      )}
      {datos && (
        <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} total={datos.total}
          onCambiar={(pagina) => void consultar(filtro, pagina)} />
      )}
      <p className="caption auditoria__nota">
        <Icono src={iconoCandado} tamano={16} />
        {t('auditoria.inalterable')}
      </p>
    </LayoutAdmin>
  )
}
