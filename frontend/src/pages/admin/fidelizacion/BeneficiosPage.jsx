import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoMas from '../../../assets/icons/mas.svg'
import { activarCupon, crearCupon, crearNivel, editarCupon, editarNivel, obtenerBeneficios, retirarNivel } from '../../../api/fidelizacion.js'
import Alerta from '../../../components/Alerta.jsx'
import Boton from '../../../components/Boton.jsx'
import Campo from '../../../components/Campo.jsx'
import Cargando from '../../../components/Cargando.jsx'
import Dialogo from '../../../components/Dialogo.jsx'
import DialogoFormulario from '../../../components/DialogoFormulario.jsx'
import EstadoVacio from '../../../components/EstadoVacio.jsx'
import LayoutAdmin from '../../../components/layout/LayoutAdmin.jsx'
import { diaLima, fecha, precio, unidades } from '../../../formato.js'
import { textoBeneficio } from '../../../fidelizacion.js'
import { mensajeError } from '../../../i18n/errores.js'
import '../../productor/productor.css'
import '../admin.css'
import {
  beneficioVacio, CamposBeneficio, cuerpoBeneficio, enFiltro, Estado, FILTROS_CUPONES, PestanasEstado, SeccionesFidelizacion, TEXTOS,
  textoPeriodo, useFormulario, validarBeneficio,
} from './comun.jsx'
import './fidelizacion.css'

const B = `${TEXTOS}.beneficios`
const ENTERO = /^\d+$/
const DECIMAL = /^\d+(\.\d{1,2})?$/

/**
 * CCPIP-88 y CCPIP-89: los beneficios que el sub administrador configura fuera de las campanas. Los niveles por
 * pedidos entregados multiplican los puntos; los cupones del catalogo se canjean con puntos por un descuento.
 */
export default function BeneficiosPage({ path }) {
  const { t } = useTranslation()
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [nivel, setNivel] = useState(null)
  const [cupon, setCupon] = useState(null)
  const [retirar, setRetirar] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [filtro, setFiltro] = useState('TODOS')

  useEffect(() => {
    obtenerBeneficios().then(setDatos).catch(setError)
  }, [])

  const listo = (parte, titulo) => (lista) => {
    setDatos((d) => ({ ...d, [parte]: lista }))
    setNivel(null)
    setCupon(null)
    setAviso({ tono: 'exito', titulo })
  }
  async function accion(llamar, parte, titulo) {
    setOcupado(true)
    try {
      listo(parte, titulo)(await llamar())
    } catch (err) {
      setAviso({ tono: 'error', titulo: t(`${B}.errorGuardar`), descripcion: mensajeError(err) })
    } finally {
      setOcupado(false)
      setRetirar(null)
    }
  }

  return (
    <LayoutAdmin path={path} migas={`${t('admin.nav.fidelizacion')} / ${t(`${TEXTOS}.secciones.beneficios`)}`} titulo={t(`${TEXTOS}.titulo`)}>
      <SeccionesFidelizacion activa="beneficios" />
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {error && <Alerta tono="error" titulo={t(`${B}.errorCargar`)} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos && (
        <>
          <section className="productor__seccion" aria-labelledby="beneficios-niveles">
            <div className="fidelizacion__cabecera">
              <div>
                <h2 id="beneficios-niveles" className="heading-h3">{t(`${B}.niveles.titulo`)}</h2>
                <p className="body-s fidelizacion__nota-seccion">{t(`${B}.niveles.texto`)}</p>
              </div>
              <Boton tamano="M" tipo="secundario" icono={iconoMas} onClick={() => setNivel({})}>{t(`${B}.niveles.nuevo`)}</Boton>
            </div>
            {datos.niveles.length === 0
              ? <EstadoVacio titulo={t(`${B}.niveles.vacio`)} descripcion={t(`${B}.niveles.vacioTexto`)} />
              : <TablaNiveles niveles={datos.niveles} onEditar={setNivel} onRetirar={setRetirar} />}
          </section>
          <section className="productor__seccion" aria-labelledby="beneficios-cupones">
            <div className="fidelizacion__cabecera">
              <div>
                <h2 id="beneficios-cupones" className="heading-h3">{t(`${B}.cupones.titulo`)}</h2>
                <p className="body-s fidelizacion__nota-seccion">{t(`${B}.cupones.texto`)}</p>
              </div>
              <Boton tamano="M" tipo="secundario" icono={iconoMas} onClick={() => setCupon({})}>{t(`${B}.cupones.nuevo`)}</Boton>
            </div>
            {datos.cupones.length === 0
              ? <EstadoVacio titulo={t(`${B}.cupones.vacio`)} descripcion={t(`${B}.cupones.vacioTexto`)} />
              : (
                <>
                  <PestanasEstado etiqueta={t(`${B}.cupones.filtrar`)} filtros={FILTROS_CUPONES} items={datos.cupones} valor={filtro}
                    onCambiar={setFiltro} textos={`${B}.cupones.filtro`} />
                  <TablaCupones cupones={datos.cupones.filter(enFiltro(FILTROS_CUPONES, filtro))} onEditar={setCupon} ocupado={ocupado}
                    onActivar={(c) => accion(() => activarCupon(c.id, !c.activo), 'cupones',
                      t(`${B}.cupones.${c.activo ? 'desactivado' : 'activado'}`, { nombre: c.nombre }))} />
                </>
              )}
          </section>
        </>
      )}
      {nivel && <DialogoNivel nivel={nivel} onCerrar={() => setNivel(null)}
        onListo={listo('niveles', t(`${B}.niveles.${nivel.id ? 'editado' : 'creado'}`))} />}
      {cupon && <DialogoCupon cupon={cupon} hoy={diaLima()} onCerrar={() => setCupon(null)}
        onListo={listo('cupones', t(`${B}.cupones.${cupon.id ? 'editado' : 'creado'}`))} />}
      <Dialogo abierto={Boolean(retirar)} titulo={t(`${B}.niveles.retirarTitulo`)} confirmar={t(`${B}.niveles.retirar`)}
        tipoConfirmar="destructivo" ocupado={ocupado} onCancelar={() => setRetirar(null)}
        onConfirmar={() => accion(() => retirarNivel(retirar.id), 'niveles', t(`${B}.niveles.retirado`, { nombre: retirar.nombre }))}>
        {retirar && <p>{t(`${B}.niveles.retirarTexto`, { nombre: retirar.nombre })}</p>}
      </Dialogo>
    </LayoutAdmin>
  )
}

function TablaNiveles({ niveles, onEditar, onRetirar }) {
  const { t } = useTranslation()
  const N = `${B}.niveles`
  return (
    <div className="tabla fidelizacion__tabla" role="table" aria-labelledby="beneficios-niveles">
      <div className="tabla__fila tabla__fila--encabezado" role="row">
        <span className="tabla__celda tabla__celda--flex" role="columnheader">{t(`${N}.col.nombre`)}</span>
        <span className="tabla__celda fidelizacion__col-periodo" role="columnheader">{t(`${N}.col.desde`)}</span>
        <span className="tabla__celda admin__num" role="columnheader">{t(`${N}.col.multiplicador`)}</span>
        <span className="tabla__celda fidelizacion__col-acciones" role="columnheader" aria-label={t(`${TEXTOS}.col.acciones`)} />
      </div>
      {niveles.map((n) => (
        <div key={n.id} className="tabla__fila" role="row">
          <span className="tabla__celda tabla__celda--flex fidelizacion__nombre" role="cell">{n.nombre}</span>
          <span className="tabla__celda fidelizacion__col-periodo" role="cell">{t(`${N}.desdePedidos`, { count: n.pedidosMinimos })}</span>
          <span className="tabla__celda admin__num" role="cell">×{unidades(n.multiplicador)}</span>
          <span className="tabla__celda fidelizacion__col-acciones" role="cell">
            <Boton tipo="terciario" tamano="S" onClick={() => onEditar(n)} aria-label={t(`${TEXTOS}.editarDe`, { nombre: n.nombre })}>{t(`${TEXTOS}.editar`)}</Boton>
            <Boton tipo="terciario" tamano="S" peligro onClick={() => onRetirar(n)} aria-label={t(`${N}.retirarDe`, { nombre: n.nombre })}>{t(`${N}.retirar`)}</Boton>
          </span>
        </div>
      ))}
    </div>
  )
}

function TablaCupones({ cupones, onEditar, onActivar, ocupado }) {
  const { t } = useTranslation()
  const C = `${B}.cupones`
  if (!cupones.length) return <p className="body-s fidelizacion__sin-filtro">{t(`${C}.sinFiltro`)}</p>
  return (
    <div className="tabla fidelizacion__tabla" role="table" aria-labelledby="beneficios-cupones">
      <div className="tabla__fila tabla__fila--encabezado" role="row">
        <span className="tabla__celda tabla__celda--flex fidelizacion__col-campana" role="columnheader">{t(`${C}.col.cupon`)}</span>
        <span className="tabla__celda fidelizacion__col-periodo" role="columnheader">{t(`${TEXTOS}.col.periodo`)}</span>
        <span className="tabla__celda admin__num" role="columnheader">{t(`${C}.col.costo`)}</span>
        <span className="tabla__celda admin__num" role="columnheader">{t(`${C}.col.canjes`)}</span>
        <span className="tabla__celda admin__num" role="columnheader">{t(`${C}.col.descuento`)}</span>
        <span className="tabla__celda fidelizacion__col-estado" role="columnheader">{t(`${TEXTOS}.col.estado`)}</span>
        <span className="tabla__celda fidelizacion__col-acciones" role="columnheader" aria-label={t(`${TEXTOS}.col.acciones`)} />
      </div>
      {cupones.map((c) => (
        <div key={c.id} className="tabla__fila" role="row">
          <span className="tabla__celda tabla__celda--flex fidelizacion__col-campana" role="cell">
            <span className="fidelizacion__nombre">{c.nombre}</span>
            <span className="caption">{textoBeneficio(t, c)} · {t(`${C}.vigencia`, { count: c.vigenciaDias })}</span>
          </span>
          <span className="tabla__celda fidelizacion__col-periodo" role="cell">
            {c.disponibleHasta ? textoPeriodo(t, { desde: c.disponibleDesde, hasta: c.disponibleHasta }) : t(`${C}.desdeSinFin`, { fecha: fecha(c.disponibleDesde) })}
          </span>
          <span className="tabla__celda admin__num" role="cell">{t(`${C}.puntos`, { puntos: unidades(c.costoPuntos) })}</span>
          <span className="tabla__celda admin__num" role="cell">{t(`${C}.canjesUsados`, { canjes: c.canjes, usados: c.usados })}</span>
          <span className="tabla__celda admin__num" role="cell">{precio(c.descuentoOtorgado)}</span>
          <span className="tabla__celda fidelizacion__col-estado" role="cell">
            <Estado estado={c.estado} textos={`${C}.estado`} />
          </span>
          <span className="tabla__celda fidelizacion__col-acciones" role="cell">
            <Boton tipo="terciario" tamano="S" onClick={() => onEditar(c)} aria-label={t(`${TEXTOS}.editarDe`, { nombre: c.nombre })}>{t(`${TEXTOS}.editar`)}</Boton>
            {/* Uno que termino por su fecha se reabre editando el periodo */}
            {(!c.activo || c.estado !== 'FINALIZADO') && (
              <Boton tipo="terciario" tamano="S" peligro={c.activo} disabled={ocupado} onClick={() => onActivar(c)}
                aria-label={t(`${C}.${c.activo ? 'desactivarDe' : 'activarDe'}`, { nombre: c.nombre })}>
                {t(`${C}.${c.activo ? 'desactivar' : 'activar'}`)}
              </Boton>
            )}
          </span>
        </div>
      ))}
    </div>
  )
}

/** Crea o edita un nivel: nombre, desde cuantos pedidos entregados y el multiplicador de puntos. */
function DialogoNivel({ nivel, onCerrar, onListo }) {
  const { t } = useTranslation()
  const N = `${B}.niveles`
  const { f, set, errores, enviando, error, enviar } = useFormulario({
    nombre: nivel.nombre ?? '', pedidosMinimos: nivel.pedidosMinimos ? String(nivel.pedidosMinimos) : '',
    multiplicador: nivel.multiplicador ? String(Number(nivel.multiplicador)) : '',
  }, onListo)
  const validar = (v) => {
    const e = {}
    if (!v.nombre.trim()) e.nombre = t(`${N}.validacion.nombre`)
    if (!ENTERO.test(v.pedidosMinimos) || Number(v.pedidosMinimos) < 1) e.pedidosMinimos = t(`${N}.validacion.pedidos`)
    const m = v.multiplicador.trim().replace(',', '.')
    if (!DECIMAL.test(m) || Number(m) <= 1 || Number(m) > 5) e.multiplicador = t(`${N}.validacion.multiplicador`)
    return e
  }
  const guardar = (evento) => enviar(evento, validar, (v) => {
    const cuerpo = { nombre: v.nombre.trim(), pedidosMinimos: Number(v.pedidosMinimos), multiplicador: Number(v.multiplicador.replace(',', '.')) }
    return nivel.id ? editarNivel(nivel.id, cuerpo) : crearNivel(cuerpo)
  })
  return (
    <DialogoFormulario titulo={t(`${N}.${nivel.id ? 'editarTitulo' : 'nuevo'}`)} confirmar={t(`${B}.guardar`)} enviando={enviando}
      onEnviar={guardar} onCancelar={onCerrar} error={error} tituloError={t(`${B}.errorGuardar`)} className="fidelizacion__grupo">
      <Campo etiqueta={t(`${N}.nombre`)} value={f.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={40} placeholder={t(`${N}.nombreEjemplo`)} />
      <div className="fidelizacion__fila">
        <Campo etiqueta={t(`${N}.pedidos`)} value={f.pedidosMinimos} onChange={set('pedidosMinimos')} error={errores.pedidosMinimos} inputMode="numeric" maxLength={5} />
        <Campo etiqueta={t(`${N}.multiplicador`)} value={f.multiplicador} onChange={set('multiplicador')} error={errores.multiplicador}
          inputMode="decimal" maxLength={4} placeholder="1.5" ayuda={t(`${N}.multiplicadorAyuda`)} />
      </div>
    </DialogoFormulario>
  )
}

/** Crea o edita un cupon del catalogo: lo que cuesta en puntos, lo que descuenta y cuantos dias vale tras canjearlo. */
function DialogoCupon({ cupon, hoy, onCerrar, onListo }) {
  const { t } = useTranslation()
  const C = `${B}.cupones`
  const { f, set, errores, enviando, error, enviar } = useFormulario({
    nombre: cupon.nombre ?? '', descripcion: cupon.descripcion ?? '', costoPuntos: cupon.costoPuntos ? String(cupon.costoPuntos) : '',
    vigenciaDias: cupon.vigenciaDias ? String(cupon.vigenciaDias) : '30', ...beneficioVacio(cupon.id ? cupon : null),
    disponibleDesde: cupon.disponibleDesde ?? hoy, disponibleHasta: cupon.disponibleHasta ?? '',
  }, onListo)
  const validar = (v) => {
    const e = validarBeneficio(t, v)
    if (!v.nombre.trim()) e.nombre = t(`${C}.validacion.nombre`)
    if (!ENTERO.test(v.costoPuntos) || Number(v.costoPuntos) < 1) e.costoPuntos = t(`${C}.validacion.costo`)
    if (!ENTERO.test(v.vigenciaDias) || Number(v.vigenciaDias) < 1 || Number(v.vigenciaDias) > 365) e.vigenciaDias = t(`${C}.validacion.vigencia`)
    if (!v.disponibleDesde) e.disponibleDesde = t(`${C}.validacion.desde`)
    else if (v.disponibleDesde !== cupon.disponibleDesde && v.disponibleDesde < hoy) e.disponibleDesde = t(`${C}.validacion.desdePasado`)
    if (v.disponibleHasta && v.disponibleDesde && v.disponibleHasta < v.disponibleDesde) e.disponibleHasta = t(`${C}.validacion.hastaAntes`)
    return e
  }
  const guardar = (evento) => enviar(evento, validar, (v) => {
    const cuerpo = {
      nombre: v.nombre.trim(), descripcion: v.descripcion.trim() || null, costoPuntos: Number(v.costoPuntos),
      vigenciaDias: Number(v.vigenciaDias), beneficio: cuerpoBeneficio(v),
      disponibleDesde: v.disponibleDesde, disponibleHasta: v.disponibleHasta || null,
    }
    return cupon.id ? editarCupon(cupon.id, cuerpo) : crearCupon(cuerpo)
  })
  return (
    <DialogoFormulario titulo={t(`${C}.${cupon.id ? 'editarTitulo' : 'nuevo'}`)} confirmar={t(`${B}.guardar`)} enviando={enviando}
      onEnviar={guardar} onCancelar={onCerrar} error={error} tituloError={t(`${B}.errorGuardar`)} className="fidelizacion__grupo">
      <Campo etiqueta={t(`${C}.nombre`)} value={f.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={80} placeholder={t(`${C}.nombreEjemplo`)} />
      <Campo etiqueta={t(`${C}.descripcion`)} tipo="area" rows={2} value={f.descripcion} onChange={set('descripcion')}
        error={errores.descripcion} maxLength={300} ayuda={t(`${C}.descripcionAyuda`)} />
      <div className="fidelizacion__fila">
        <Campo etiqueta={t(`${C}.costo`)} value={f.costoPuntos} onChange={set('costoPuntos')} error={errores.costoPuntos} inputMode="numeric" maxLength={7} />
        <Campo etiqueta={t(`${C}.vigenciaDias`)} value={f.vigenciaDias} onChange={set('vigenciaDias')} error={errores.vigenciaDias}
          inputMode="numeric" maxLength={3} ayuda={t(`${C}.vigenciaAyuda`)} />
      </div>
      <CamposBeneficio f={f} set={set} errores={errores} />
      <div className="fidelizacion__fila">
        <Campo etiqueta={t(`${C}.disponibleDesde`)} type="date" min={hoy} value={f.disponibleDesde} onChange={set('disponibleDesde')}
          error={errores.disponibleDesde} />
        <Campo etiqueta={t(`${C}.disponibleHasta`)} type="date" min={f.disponibleDesde || hoy} value={f.disponibleHasta}
          onChange={set('disponibleHasta')} error={errores.disponibleHasta} ayuda={t(`${C}.disponibleHastaAyuda`)} />
      </div>
      {cupon.id && cupon.canjes > 0 && <p className="caption">{t(`${C}.conservan`)}</p>}
    </DialogoFormulario>
  )
}
