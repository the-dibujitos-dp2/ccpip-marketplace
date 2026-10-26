import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoMas from '../../assets/icons/mas.svg'
import { buscarCuentas } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Buscador from '../../components/Buscador.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaCorta } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import { EstadoCuenta } from './cuentas.jsx'
import '../productor/productor.css'
import './admin.css'

const TODAS = 'TODAS'
const ESTADOS = ['ACTIVA', 'SUSPENDIDA', 'DESACTIVADA']

/**
 * SA-03 y SA-04: cuentas de la plataforma. CCPIP-95: cada rol busca solo las cuentas de su alcance, que
 * decide el servidor (las pestanas son los roles que este devuelve). CCPIP-124 y 125: el superadministrador
 * consulta sub administradores y administradores de punto con sus puntos, y es quien crea cuentas.
 */
export default function CuentasPage({ path }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const esSuper = user.role === 'SUPER_ADMIN'
  const [rol, setRol] = useState(esSuper ? 'SUB_ADMIN' : TODAS)
  const [estado, setEstado] = useState('')
  const [texto, setTexto] = useState('')
  const [q, setQ] = useState('')
  const [pagina, setPagina] = useState(0)
  const [datos, setDatos] = useState(null)
  const [roles, setRoles] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(async (filtros) => {
    setError(null)
    try {
      const r = await buscarCuentas(filtros)
      setDatos(r)
      setRoles(r.roles)
    } catch (e) {
      setError(e)
    }
  }, [])

  useEffect(() => {
    cargar({ q, rol: rol === TODAS ? undefined : rol, estado: estado || undefined, pagina })
  }, [cargar, q, rol, estado, pagina])

  const filtrar = (cambio) => (valor) => {
    cambio(valor)
    setPagina(0)
  }

  const conPuntos = rol === 'ADMIN_PUNTO' || (rol === TODAS && roles?.includes('ADMIN_PUNTO'))
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.cuentas')} titulo={t('cuentas.titulo')}>
      <div className="cuentas__barra">
        <p className="body-s cuentas__intro">{t(esSuper ? 'cuentas.introSuper' : 'cuentas.introSub')}</p>
        {esSuper && (
          <Boton tamano="M" icono={iconoMas} to={RUTAS.adminCuentaNueva}>
            {t('cuentas.nueva')}
          </Boton>
        )}
      </div>
      {roles && (
        <Pestanas
          etiqueta={t('cuentas.pestanasEtiqueta')}
          valor={rol}
          onCambiar={filtrar(setRol)}
          opciones={[{ valor: TODAS, etiqueta: t('cuentas.todas') }, ...roles.map((r) => ({ valor: r, etiqueta: t(`cuentas.rolPlural.${r}`) }))]}
        />
      )}
      <div className="cuentas__filtros">
        <Buscador
          id="buscar-cuentas"
          etiqueta={t('cuentas.buscar')}
          placeholder={t('cuentas.buscarEjemplo')}
          value={texto}
          maxLength={100}
          onChange={(e) => setTexto(e.target.value)}
          onBuscar={(e) => {
            e.preventDefault()
            filtrar(setQ)(texto.trim())
          }}
        />
        <Campo
          etiqueta={t('cuentas.filtroEstado')}
          tipo="selector"
          placeholder={t('cuentas.todosLosEstados')}
          value={estado}
          onChange={(e) => filtrar(setEstado)(e.target.value)}
          opciones={ESTADOS.map((e) => ({ valor: e, etiqueta: t(`cuentas.estado.${e}`) }))}
        />
      </div>
      {error && <Alerta tono="error" titulo={mensajeError(error, 'cuentas.errorCargar')} />}
      {!datos && !error && <Cargando />}
      {datos && datos.items.length === 0 && (
        <EstadoVacio titulo={t('cuentas.vacio')} descripcion={q || estado ? t('cuentas.vacioFiltros') : undefined} />
      )}
      {datos && datos.items.length > 0 && (
        <>
          <p className="caption" role="status">{t('cuentas.total', { count: datos.total })}</p>
          <div className="tabla" role="table" aria-label={t('cuentas.tabla')}>
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('cuentas.col.cuenta')}</span>
              <span className="tabla__celda cuentas__rol" role="columnheader">{t('cuentas.col.rol')}</span>
              {conPuntos && <span className="tabla__celda cuentas__puntos-col" role="columnheader">{t('cuentas.col.puntos')}</span>}
              <span className="tabla__celda cuentas__estado-col" role="columnheader">{t('cuentas.col.estado')}</span>
              <span className="tabla__celda cuentas__fecha" role="columnheader">{t('cuentas.col.creada')}</span>
              <span className="tabla__celda cuentas__accion" role="columnheader"><span className="visually-hidden">{t('cuentas.col.accion')}</span></span>
            </div>
            {datos.items.map((c) => (
              <div key={c.id} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex" role="cell">
                  <span className="label-m">{c.nombre}</span>
                  <span className="caption cuentas__correo">{c.correo}</span>
                </span>
                <span className="tabla__celda cuentas__rol" role="cell">{t(`roles.${c.rol}`, { defaultValue: c.rolNombre })}</span>
                {conPuntos && <span className="tabla__celda cuentas__puntos-col" role="cell">{c.puntos.length ? c.puntos.join(', ') : '—'}</span>}
                <span className="tabla__celda cuentas__estado-col" role="cell"><EstadoCuenta cuenta={c} /></span>
                <span className="tabla__celda cuentas__fecha" role="cell">{fechaCorta(c.creadaEn.slice(0, 10))}</span>
                <span className="tabla__celda cuentas__accion" role="cell">
                  <Boton tipo="secundario" tamano="S" to={`${RUTAS.adminCuentas}/${c.id}`} aria-label={t('cuentas.verDe', { nombre: c.nombre })}>
                    {t('cuentas.ver')}
                  </Boton>
                </span>
              </div>
            ))}
          </div>
          <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} onCambiar={setPagina} />
        </>
      )}
      <p className="productor__nota">{t('cuentas.nota')}</p>
    </LayoutAdmin>
  )
}
