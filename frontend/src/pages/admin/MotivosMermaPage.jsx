import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoMas from '../../assets/icons/mas.svg'
import { activarMotivo, crearMotivo, desactivarMotivo, listarMotivos, modificarMotivo } from '../../api/merma.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { erroresPorCampo, mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import Paginacion from '../../components/Paginacion.jsx'

/**
 * Catalogo de causas de merma del superadministrador: consultar (CCPIP-77), crear (CCPIP-298),
 * modificar (CCPIP-299) y desactivar (CCPIP-300). La merma ya registrada guarda el nombre con el que
 * se marco, asi que ningun cambio la altera. "Vencimiento" la usa la merma automatica: no se desactiva.
 * No hay pantalla en el Figma: sigue la tabla y los dialogos del panel de administracion.
 */
export default function MotivosMermaPage({ path }) {
  const { t } = useTranslation()
  const { datos, setDatos, error, recargar } = useConsulta(listarMotivos)
  const [editando, setEditando] = useState(null)
  const [desactivando, setDesactivando] = useState(null)
  const [alerta, setAlerta] = useState(null)
  // El id del motivo que se esta activando o desactivando (CCPIP-413: el aro va solo en su boton)
  const [ocupado, setOcupado] = useState(null)

  const activos = datos?.filter((m) => m.activo).length ?? 0
  const paginas = usePaginacionLocal(datos ?? [])

  function reemplazar(motivo) {
    setDatos((lista) => {
      const existe = lista.some((m) => m.id === motivo.id)
      return existe ? lista.map((m) => (m.id === motivo.id ? motivo : m)) : [...lista, motivo]
    })
  }

  function alGuardar(motivo, nuevo) {
    setEditando(null)
    reemplazar(motivo)
    setAlerta({ tono: 'exito', titulo: t(nuevo ? 'admin.motivosMerma.creado' : 'admin.motivosMerma.modificado', { nombre: motivo.nombre }) })
  }

  function cambiarEstado(motivo, activar) {
    setOcupado(motivo.id)
    ;(activar ? activarMotivo(motivo.id) : desactivarMotivo(motivo.id))
      .then((m) => {
        reemplazar(m)
        setDesactivando(null)
        setAlerta({ tono: 'exito', titulo: t(activar ? 'admin.motivosMerma.activado' : 'admin.motivosMerma.desactivado', { nombre: m.nombre }) })
      })
      .catch((e) => {
        setDesactivando(null)
        setAlerta({ tono: 'error', titulo: t('admin.motivosMerma.errorEstado'), descripcion: mensajeError(e) })
      })
      .finally(() => setOcupado(null))
  }

  const titulo = t('admin.motivosMerma.titulo')
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.motivosMerma')} titulo={titulo}>
      <div className="motivos__barra">
        <p className="body-m">{datos && t('admin.motivosMerma.resumen', { activos, total: datos.length })}</p>
        <Boton tamano="M" icono={iconoMas} onClick={() => setEditando({})}>{t('admin.motivosMerma.nuevo')}</Boton>
      </div>
      <p className="body-s">{t('admin.motivosMerma.intro')}</p>

      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {error && (
        <EstadoVacio titulo={t('admin.motivosMerma.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!datos && !error && <Cargando />}

      {datos?.length > 0 && (
        <div className="tabla" role="table" aria-label={titulo}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.motivosMerma.col.nombre')}</span>
            <span className="tabla__celda motivos__col-ingles" role="columnheader">{t('admin.motivosMerma.col.ingles')}</span>
            <span className="tabla__celda motivos__col-detalle" role="columnheader">{t('admin.motivosMerma.col.detalle')}</span>
            <span className="tabla__celda motivos__col-usos" role="columnheader">{t('admin.motivosMerma.col.usos')}</span>
            <span className="tabla__celda motivos__col-estado" role="columnheader">{t('admin.motivosMerma.col.estado')}</span>
            <span className="tabla__celda tabla__celda--accion motivos__col-acciones" role="columnheader">{t('admin.motivosMerma.col.acciones')}</span>
          </div>
          {paginas.filas.map((m) => (
            <div key={m.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{m.nombre}</span>
                {m.sistema && <span className="caption motivos__sistema">{t('admin.motivosMerma.sistema')}</span>}
              </span>
              <span className="tabla__celda motivos__col-ingles" role="cell">{m.nombreEn}</span>
              <span className="tabla__celda motivos__col-detalle" role="cell">{t(m.requiereDetalle ? 'admin.motivosMerma.si' : 'admin.motivosMerma.no')}</span>
              <span className="tabla__celda motivos__col-usos" role="cell">{m.usos}</span>
              <span className="tabla__celda motivos__col-estado" role="cell">
                <Badge tono={m.activo ? 'exito' : 'neutro'}>{t(m.activo ? 'admin.motivosMerma.activa' : 'admin.motivosMerma.inactiva')}</Badge>
              </span>
              <span className="tabla__celda tabla__celda--accion motivos__col-acciones" role="cell">
                <Boton tipo="terciario" tamano="S" onClick={() => setEditando(m)} aria-label={t('admin.motivosMerma.editarDe', { nombre: m.nombre })}>
                  {t('admin.motivosMerma.editar')}
                </Boton>
                {!m.sistema && (m.activo ? (
                  <Boton tipo="terciario" tamano="S" onClick={() => setDesactivando(m)} aria-label={t('admin.motivosMerma.desactivarDe', { nombre: m.nombre })}>
                    {t('admin.motivosMerma.desactivar')}
                  </Boton>
                ) : (
                  <Boton tipo="terciario" tamano="S" disabled={ocupado !== null} cargando={ocupado === m.id} onClick={() => cambiarEstado(m, true)} aria-label={t('admin.motivosMerma.activarDe', { nombre: m.nombre })}>
                    {t('admin.motivosMerma.activar')}
                  </Boton>
                ))}
              </span>
            </div>
          ))}
        </div>
      )}
      {datos && <Paginacion {...paginas} />}

      {editando && <DialogoMotivo motivo={editando} onGuardado={alGuardar} onCancelar={() => setEditando(null)} />}

      <Dialogo
        abierto={!!desactivando}
        titulo={t('admin.motivosMerma.confirmarTitulo', { nombre: desactivando?.nombre })}
        confirmar={t('admin.motivosMerma.desactivar')}
        tipoConfirmar="destructivo"
        onConfirmar={() => cambiarEstado(desactivando, false)}
        onCancelar={() => setDesactivando(null)}
        ocupado={ocupado !== null}
      >
        <p>{t('admin.motivosMerma.confirmarTexto', { count: desactivando?.usos ?? 0 })}</p>
      </Dialogo>
    </LayoutAdmin>
  )
}

/** Alta (sin `id`) o cambio de una causa, con su nombre en ambos idiomas. */
function DialogoMotivo({ motivo, onGuardado, onCancelar }) {
  const { t } = useTranslation()
  const nuevo = motivo.id == null
  const [nombre, setNombre] = useState(motivo.nombre ?? '')
  const [nombreEn, setNombreEn] = useState(motivo.nombreEn ?? '')
  const [requiereDetalle, setRequiereDetalle] = useState(motivo.requiereDetalle ?? false)
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  function enviar(e) {
    e?.preventDefault()
    const faltan = {}
    if (!nombre.trim()) faltan.nombre = t('admin.motivosMerma.faltaNombre')
    if (!nombreEn.trim()) faltan.nombreEn = t('admin.motivosMerma.faltaNombreEn')
    setErrores(faltan)
    setError(null)
    if (Object.keys(faltan).length > 0) return
    setEnviando(true)
    const datos = { nombre: nombre.trim(), nombreEn: nombreEn.trim(), requiereDetalle }
    ;(nuevo ? crearMotivo(datos) : modificarMotivo(motivo.id, datos))
      .then((m) => onGuardado(m, nuevo))
      .catch((err) => {
        if (err.body?.campos) setErrores(erroresPorCampo(err))
        else setError(err)
      })
      .finally(() => setEnviando(false))
  }

  return (
    <DialogoFormulario
      titulo={t(nuevo ? 'admin.motivosMerma.nuevo' : 'admin.motivosMerma.editarTitulo')}
      confirmar={t('admin.motivosMerma.guardar')}
      enviando={enviando}
      onEnviar={enviar}
      onCancelar={onCancelar}
      className="motivos__formulario"
      error={error}
      tituloError={t('admin.motivosMerma.errorGuardar')}
    >
      {!nuevo && motivo.usos > 0 && <p className="caption">{t('admin.motivosMerma.conservaHistorial', { count: motivo.usos })}</p>}
      <Campo etiqueta={t('admin.motivosMerma.campoNombre')} value={nombre} maxLength={60}
        onChange={(e) => { setNombre(e.target.value); setErrores((x) => ({ ...x, nombre: undefined })) }} error={errores.nombre} />
      <Campo etiqueta={t('admin.motivosMerma.campoNombreEn')} value={nombreEn} maxLength={60}
        onChange={(e) => { setNombreEn(e.target.value); setErrores((x) => ({ ...x, nombreEn: undefined })) }} error={errores.nombreEn} />
      <label className="motivos__check">
        <input type="checkbox" checked={requiereDetalle} onChange={(e) => setRequiereDetalle(e.target.checked)} />
        {t('admin.motivosMerma.campoDetalle')}
      </label>
    </DialogoFormulario>
  )
}
