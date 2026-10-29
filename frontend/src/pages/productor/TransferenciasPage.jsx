import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoMas from '../../assets/icons/mas.svg'
import { despacharTransferencia, listarTransferencias, obtenerTransferencia, prepararTransferencia } from '../../api/productor.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import DetalleTransferencia, { EstadoTransferencia, nombreLugar } from '../../components/DetalleTransferencia.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import { fechaHora, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './TransferenciasPage.css'

/** La accion que sigue segun el estado: preparar (CCPIP-164) y despachar (CCPIP-362). */
const ACCIONES = {
  INICIADA: { clave: 'preparar', enviar: prepararTransferencia },
  PREPARADA: { clave: 'despachar', enviar: despacharTransferencia },
}

/**
 * PW-17 · Mis transferencias: en curso e historial (CCPIP-73, 277, 164, 362). El servidor envia solo
 * las del productor de la sesion (RNF-05). Preparar descuenta el lote de origen; despachar no vuelve
 * a descontar; la recepcion la registra el punto de destino.
 */
export default function TransferenciasPage({ path, search = '' }) {
  const { t } = useTranslation()
  const creada = new URLSearchParams(search).get('creada')
  const [vista, setVista] = useState('EN_CURSO')
  const consultar = useCallback(() => listarTransferencias(vista), [vista])
  const { datos, setDatos, error, recargar } = useConsulta(consultar)
  const [abierta, setAbierta] = useState(null) // detalle con historial
  const [cargandoDetalle, setCargandoDetalle] = useState(null) // CCPIP-413: la que espera su detalle
  const [confirmando, setConfirmando] = useState(null) // { transferencia, accion }
  const [ocupado, setOcupado] = useState(false)
  const [aviso, setAviso] = useState(creada ? { tono: 'exito', titulo: t('productor.transferencias.creada', { codigo: creada }) } : null)

  async function verDetalle(tr) {
    if (abierta?.id === tr.id) {
      setAbierta(null)
      return
    }
    setCargandoDetalle(tr.id)
    try {
      setAbierta(await obtenerTransferencia(tr.id))
    } catch (e) {
      setAviso({ tono: 'error', titulo: t('productor.transferencias.errorDetalle'), descripcion: mensajeError(e, 'comun.errorConexion') })
    } finally {
      setCargandoDetalle(null)
    }
  }

  async function confirmar() {
    const { transferencia, accion } = confirmando
    setOcupado(true)
    try {
      const nueva = await accion.enviar(transferencia.id)
      setDatos((d) => d && { ...d, items: d.items.map((x) => (x.id === nueva.id ? nueva : x)) })
      if (abierta?.id === nueva.id) setAbierta(nueva)
      setAviso({ tono: 'exito', titulo: t(`productor.transferencias.${accion.clave}.hecho`, { codigo: nueva.codigo }) })
      recargar()
    } catch (e) {
      setAviso({ tono: 'error', titulo: t(`productor.transferencias.${accion.clave}.error`), descripcion: mensajeError(e, 'comun.errorConexion') })
    } finally {
      setOcupado(false)
      setConfirmando(null)
    }
  }

  const acciones = (
    <Boton tamano="M" icono={iconoMas} to={RUTAS.productorTransferenciaNueva}>{t('productor.transferencias.nueva')}</Boton>
  )

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.transferencias')} titulo={t('productor.transferencias.titulo')} acciones={acciones}>
      {aviso && <Alerta tono={aviso.tono} titulo={aviso.titulo} descripcion={aviso.descripcion} onCerrar={() => setAviso(null)} />}

      <Pestanas
        etiqueta={t('productor.transferencias.vistas')}
        valor={vista}
        onCambiar={(v) => {
          setVista(v)
          setAbierta(null)
        }}
        opciones={[
          { valor: 'EN_CURSO', etiqueta: t('productor.transferencias.enCurso', { total: datos?.enCurso ?? 0 }) },
          { valor: 'HISTORIAL', etiqueta: t('productor.transferencias.historial', { total: datos?.historial ?? 0 }) },
        ]}
      />

      {error && (
        <EstadoVacio titulo={t('productor.transferencias.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!datos && !error && <Cargando />}

      {!error && datos?.items.length === 0 && (
        <EstadoVacio
          titulo={t(vista === 'EN_CURSO' ? 'productor.transferencias.vacioEnCurso' : 'productor.transferencias.vacioHistorial')}
          descripcion={t('productor.transferencias.vacioTexto')}
        />
      )}

      {!error && datos?.items.length > 0 && (
        <ul className="transferencias__lista" aria-label={t('productor.transferencias.lista')}>
          {datos.items.map((tr) => {
            const accion = ACCIONES[tr.estado]
            return (
              <li key={tr.id} className="transferencias__item">
                <div className="transferencias__fila">
                  <span className="transferencias__codigo label-m">{tr.codigo}</span>
                  <span className="transferencias__ruta">
                    <span className="caption">{t(`transferencias.tipo.${tr.tipo}`)}</span>
                    <span className="body-s">{t('transferencias.ruta', { origen: nombreLugar(tr.origen, t), destino: nombreLugar(tr.destino, t) })}</span>
                  </span>
                  <span className="transferencias__cantidad">
                    {t('productor.transferencias.resumen', { lotes: tr.items.length, cantidad: unidades(tr.cantidad) })}
                  </span>
                  <span className="transferencias__fecha caption">{fechaHora(tr.actualizadaEn)}</span>
                  <EstadoTransferencia estado={tr.estado} />
                  <span className="transferencias__acciones">
                    <Boton tipo="terciario" tamano="S" aria-expanded={abierta?.id === tr.id} cargando={cargandoDetalle === tr.id} onClick={() => verDetalle(tr)}>
                      {t(abierta?.id === tr.id ? 'transferencias.ocultarDetalle' : 'transferencias.verDetalle')}
                    </Boton>
                    {accion && (
                      <Boton
                        tipo="secundario"
                        tamano="S"
                        aria-label={t(`productor.transferencias.${accion.clave}.etiqueta`, { codigo: tr.codigo })}
                        onClick={() => setConfirmando({ transferencia: tr, accion })}
                      >
                        {t(`productor.transferencias.${accion.clave}.boton`)}
                      </Boton>
                    )}
                  </span>
                </div>
                {tr.estado === 'SOLICITADA' && <p className="productor__nota">{t('transferencias.pendienteAprobador')}</p>}
                {tr.estado === 'DESPACHADA' && <p className="productor__nota">{t('productor.transferencias.enTransito')}</p>}
                {abierta?.id === tr.id && <DetalleTransferencia transferencia={abierta} />}
              </li>
            )
          })}
        </ul>
      )}

      <Dialogo
        abierto={!!confirmando}
        titulo={confirmando ? t(`productor.transferencias.${confirmando.accion.clave}.titulo`, { codigo: confirmando.transferencia.codigo }) : ''}
        confirmar={confirmando ? t(`productor.transferencias.${confirmando.accion.clave}.boton`) : undefined}
        onConfirmar={confirmar}
        onCancelar={() => setConfirmando(null)}
        ocupado={ocupado}
      >
        {confirmando && (
          <p>
            {t(`productor.transferencias.${confirmando.accion.clave}.texto`, {
              cantidad: unidades(confirmando.transferencia.cantidad),
              destino: confirmando.transferencia.destino.nombre,
            })}
          </p>
        )}
      </Dialogo>
    </PaginaProductor>
  )
}
