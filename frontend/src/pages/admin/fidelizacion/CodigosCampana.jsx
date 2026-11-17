import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoMas from '../../../assets/icons/mas.svg'
import { codigosCampana, crearCodigo, desactivarCodigo } from '../../../api/fidelizacion.js'
import Alerta from '../../../components/Alerta.jsx'
import Badge from '../../../components/Badge.jsx'
import Boton from '../../../components/Boton.jsx'
import Campo from '../../../components/Campo.jsx'
import DialogoFormulario from '../../../components/DialogoFormulario.jsx'
import { precio, unidades } from '../../../formato.js'
import { textoBeneficio } from '../../../fidelizacion.js'
import { mensajeError } from '../../../i18n/errores.js'
import { beneficioVacio, CamposBeneficio, cuerpoBeneficio, TEXTOS, useFormulario, validarBeneficio } from './comun.jsx'

const C = `${TEXTOS}.codigos`
const CODIGO = /^[A-Za-z0-9]{4,20}$/
const ENTERO = /^\d+$/

/**
 * Los codigos de descuento de una campana (p. ej. CACAO10), para difundirla: valen mientras rige, con un limite de
 * usos total y por cliente. Solo se crean en una campana que aun no termina; un codigo no se edita, se desactiva.
 */
export default function CodigosCampana({ campana }) {
  const { t } = useTranslation()
  const [codigos, setCodigos] = useState(null)
  const [nuevo, setNuevo] = useState(false)
  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const vigente = campana.estado === 'ACTIVA' || campana.estado === 'PROGRAMADA'

  useEffect(() => {
    codigosCampana(campana.id).then(setCodigos).catch((err) => setAviso({ tono: 'error', titulo: t(`${C}.errorCargar`), descripcion: mensajeError(err) }))
  }, [campana.id, t])

  async function desactivar(codigo) {
    setOcupado(true)
    try {
      setCodigos(await desactivarCodigo(campana.id, codigo.id))
      setAviso({ tono: 'exito', titulo: t(`${C}.desactivado`, { codigo: codigo.codigo }) })
    } catch (err) {
      setAviso({ tono: 'error', titulo: t(`${C}.errorGuardar`), descripcion: mensajeError(err) })
    } finally {
      setOcupado(false)
    }
  }

  return (
    <section className="productor__seccion" aria-labelledby="campana-codigos">
      <div className="fidelizacion__cabecera">
        <div>
          <h2 id="campana-codigos" className="heading-h3">{t(`${C}.titulo`)}</h2>
          <p className="body-s fidelizacion__nota-seccion">{t(`${C}.texto`)}</p>
        </div>
        {vigente && <Boton tamano="M" tipo="secundario" icono={iconoMas} onClick={() => setNuevo(true)}>{t(`${C}.nuevo`)}</Boton>}
      </div>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {codigos?.length === 0 && <p className="body-s fidelizacion__sin-filtro">{t(`${C}.vacio`)}</p>}
      {codigos?.length > 0 && (
        <div className="tabla fidelizacion__tabla" role="table" aria-labelledby="campana-codigos">
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex fidelizacion__col-campana" role="columnheader">{t(`${C}.col.codigo`)}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t(`${C}.col.usos`)}</span>
            <span className="tabla__celda admin__num" role="columnheader">{t(`${C}.col.descuento`)}</span>
            <span className="tabla__celda fidelizacion__col-estado" role="columnheader">{t(`${TEXTOS}.col.estado`)}</span>
            <span className="tabla__celda fidelizacion__col-acciones" role="columnheader" aria-label={t(`${TEXTOS}.col.acciones`)} />
          </div>
          {codigos.map((c) => (
            <div key={c.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex fidelizacion__col-campana" role="cell">
                <code className="fidelizacion__codigo">{c.codigo}</code>
                <span className="caption">{textoBeneficio(t, c)} · {t(`${C}.porCliente`, { count: c.usosPorCliente })}</span>
              </span>
              <span className="tabla__celda admin__num" role="cell">
                {c.usosMaximos ? t(`${C}.usosDe`, { usos: unidades(c.usos), maximo: unidades(c.usosMaximos) }) : unidades(c.usos)}
              </span>
              <span className="tabla__celda admin__num" role="cell">{precio(c.descuentoOtorgado)}</span>
              <span className="tabla__celda fidelizacion__col-estado" role="cell">
                <Badge tono={c.activo ? 'exito' : 'neutro'}>{t(`${C}.${c.activo ? 'activo' : 'inactivo'}`)}</Badge>
              </span>
              <span className="tabla__celda fidelizacion__col-acciones" role="cell">
                {c.activo && (
                  <Boton tipo="terciario" tamano="S" peligro disabled={ocupado} onClick={() => desactivar(c)}
                    aria-label={t(`${C}.desactivarDe`, { codigo: c.codigo })}>{t(`${C}.desactivar`)}</Boton>
                )}
              </span>
            </div>
          ))}
        </div>
      )}
      {nuevo && (
        <DialogoCodigo campana={campana} onCerrar={() => setNuevo(false)} onListo={(lista) => {
          setCodigos(lista)
          setNuevo(false)
          setAviso({ tono: 'exito', titulo: t(`${C}.creado`, { codigo: lista[0]?.codigo }) })
        }} />
      )}
    </section>
  )
}

function DialogoCodigo({ campana, onCerrar, onListo }) {
  const { t } = useTranslation()
  const { f, set, errores, enviando, error, enviar } = useFormulario(
    { codigo: '', usosMaximos: '', usosPorCliente: '1', ...beneficioVacio(null) }, onListo)
  const validar = (v) => {
    const e = validarBeneficio(t, v)
    if (!CODIGO.test(v.codigo.trim())) e.codigo = t(`${C}.validacion.codigo`)
    if (v.usosMaximos.trim() && (!ENTERO.test(v.usosMaximos) || Number(v.usosMaximos) < 1)) e.usosMaximos = t(`${C}.validacion.usos`)
    if (!ENTERO.test(v.usosPorCliente) || Number(v.usosPorCliente) < 1 || Number(v.usosPorCliente) > 100) {
      e.usosPorCliente = t(`${C}.validacion.porCliente`)
    }
    return e
  }
  const guardar = (evento) => enviar(evento, validar, (v) => crearCodigo(campana.id, {
    codigo: v.codigo.trim().toUpperCase(), beneficio: cuerpoBeneficio(v),
    usosMaximos: v.usosMaximos.trim() ? Number(v.usosMaximos) : null, usosPorCliente: Number(v.usosPorCliente),
  }))
  return (
    <DialogoFormulario titulo={t(`${C}.nuevo`)} confirmar={t(`${C}.crear`)} enviando={enviando} onEnviar={guardar}
      onCancelar={onCerrar} error={error} tituloError={t(`${C}.errorGuardar`)} className="fidelizacion__grupo">
      <Campo etiqueta={t(`${C}.codigo`)} value={f.codigo} onChange={set('codigo')} error={errores.codigo} maxLength={20}
        placeholder="CACAO10" autoComplete="off" ayuda={t(`${C}.codigoAyuda`)} />
      <CamposBeneficio f={f} set={set} errores={errores} />
      <div className="fidelizacion__fila">
        <Campo etiqueta={t(`${C}.usosMaximos`)} value={f.usosMaximos} onChange={set('usosMaximos')} error={errores.usosMaximos}
          inputMode="numeric" maxLength={7} ayuda={t(`${C}.usosMaximosAyuda`)} />
        <Campo etiqueta={t(`${C}.usosPorCliente`)} value={f.usosPorCliente} onChange={set('usosPorCliente')} error={errores.usosPorCliente}
          inputMode="numeric" maxLength={3} />
      </div>
    </DialogoFormulario>
  )
}
