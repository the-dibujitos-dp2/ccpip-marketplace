import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../assets/icons/alerta-aviso.svg'
import iconoCandado from '../assets/icons/candado.svg'
import { listarCausas } from '../api/merma.js'
import { unidades } from '../formato.js'
import { erroresPorCampo, mensajeError } from '../i18n/errores.js'
import { nuevaClave } from '../movimientos.js'
import Alerta from './Alerta.jsx'
import Campo from './Campo.jsx'
import Dialogo from './Dialogo.jsx'
import Icono from './Icono.jsx'
import './DialogoMerma.css'

const CANTIDAD = /^\d{1,9}(\.\d{1,3})?$/

/** El nombre de la causa en el idioma de la pantalla. */
export function nombreCausa(causa, i18n) {
  return i18n.resolvedLanguage === 'en' ? causa.nombreEn ?? causa.motivoEn : causa.nombre ?? causa.motivo
}

/**
 * CCPIP-76 (RF-61): "Marcar como merma" (Figma A-04) y su confirmacion (A-07). Primero la cantidad y
 * la causa; luego el resumen, que avisa que no podra revertirse, y solo entonces se envia con
 * `confirmada`. Se monta una vez por apertura, con su clave: un reintento no descuenta dos veces.
 *
 * - `lote`: { id, codigo, producto, detalle, stock }, donde `detalle` es el productor o la ubicacion.
 * - `marcar(datos)` llama al API; `onMarcada(respuesta)` recibe la merma y el saldo del lote.
 */
export default function DialogoMerma({ lote, marcar, onMarcada, onCancelar }) {
  const { t, i18n } = useTranslation()
  const [causas, setCausas] = useState(null)
  const [errorCausas, setErrorCausas] = useState(null)
  const [cantidad, setCantidad] = useState('')
  const [causaId, setCausaId] = useState('')
  const [detalle, setDetalle] = useState('')
  const [aviso, setAviso] = useState(true)
  const [confirmando, setConfirmando] = useState(false)
  const [clave] = useState(nuevaClave)
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  useEffect(() => {
    listarCausas().then(setCausas).catch(setErrorCausas)
  }, [])

  const causa = causas?.find((c) => String(c.id) === causaId)
  const valida = CANTIDAD.test(cantidad.trim()) && Number(cantidad) > 0
  const resultante = valida ? Number(lote.stock) - Number(cantidad) : null

  function continuar() {
    const nuevos = {}
    if (!valida) nuevos.cantidad = t('merma.errores.cantidad')
    else if (resultante < 0) nuevos.cantidad = t('merma.errores.excede', { stock: unidades(lote.stock) })
    if (!causa) nuevos.motivoId = t('merma.errores.causa')
    else if (causa.requiereDetalle && !detalle.trim()) nuevos.detalle = t('merma.errores.detalle')
    setErrores(nuevos)
    setError(null)
    if (Object.keys(nuevos).length === 0) setConfirmando(true)
  }

  function confirmar() {
    setEnviando(true)
    setError(null)
    marcar({
      loteId: lote.id, cantidad: cantidad.trim(), motivoId: causa.id,
      detalle: causa.requiereDetalle ? detalle.trim() : null, clave, confirmada: true,
    })
      .then(onMarcada)
      .catch((err) => {
        // Un dato rechazado vuelve al formulario para corregirlo
        setConfirmando(false)
        if (err.body?.campos) setErrores(erroresPorCampo(err))
        setError(err)
      })
      .finally(() => setEnviando(false))
  }

  if (confirmando) {
    return (
      <Dialogo
        abierto
        titulo={t('merma.confirmar.titulo')}
        confirmar={enviando ? t('merma.confirmar.marcando') : t('merma.confirmar.boton')}
        tipoConfirmar="destructivo"
        onConfirmar={confirmar}
        onCancelar={() => setConfirmando(false)}
        ocupado={enviando}
      >
        <div className="dialogo-merma__confirmar">
          <span className="dialogo-merma__icono-aviso" aria-hidden="true">
            <Icono src={iconoAlerta} tamano={20} />
          </span>
          <p>
            {t('merma.confirmar.resumen', {
              cantidad: unidades(cantidad), producto: lote.producto, lote: lote.codigo, detalle: lote.detalle,
              causa: nombreCausa(causa, i18n), antes: unidades(lote.stock), despues: unidades(resultante),
            })}
          </p>
          <p className="dialogo-merma__irreversible">
            <Icono src={iconoCandado} tamano={16} />
            {t('merma.confirmar.irreversible')}
          </p>
        </div>
      </Dialogo>
    )
  }

  return (
    <Dialogo
      abierto
      titulo={t('merma.titulo')}
      confirmar={t('merma.continuar')}
      onConfirmar={continuar}
      onCancelar={onCancelar}
      onCerrar={onCancelar}
    >
      <form className="dialogo-merma" onSubmit={(e) => { e.preventDefault(); continuar() }} noValidate>
        <div className="dialogo-merma__lote">
          <p className="label-m">{t('merma.loteTitulo', { producto: lote.producto, lote: lote.codigo })}</p>
          <p className="caption">{t('merma.loteDetalle', { detalle: lote.detalle, stock: unidades(lote.stock) })}</p>
        </div>

        <Campo
          etiqueta={t('merma.cantidad')}
          inputMode="decimal"
          value={cantidad}
          onChange={(e) => { setCantidad(e.target.value); setErrores((x) => ({ ...x, cantidad: undefined })) }}
          ayuda={resultante != null && resultante >= 0 ? t('merma.resultante', { stock: unidades(resultante) }) : undefined}
          error={errores.cantidad}
        />

        <fieldset className="dialogo-merma__causas" aria-invalid={errores.motivoId ? 'true' : undefined}>
          <legend className="campo__etiqueta">{t('merma.causa')}</legend>
          {errorCausas && <Alerta tono="error" titulo={t('merma.errorCausas')} descripcion={mensajeError(errorCausas, 'comun.errorConexion')} />}
          {causas?.map((c) => (
            <label key={c.id} className={`dialogo-merma__causa${String(c.id) === causaId ? ' dialogo-merma__causa--elegida' : ''}`}>
              <input
                type="radio"
                name="causa"
                value={c.id}
                checked={String(c.id) === causaId}
                onChange={() => { setCausaId(String(c.id)); setErrores((x) => ({ ...x, motivoId: undefined, detalle: undefined })) }}
              />
              {c.requiereDetalle ? t('merma.especificar', { causa: nombreCausa(c, i18n) }) : nombreCausa(c, i18n)}
            </label>
          ))}
          {errores.motivoId && <p className="campo__ayuda campo__ayuda--error">{errores.motivoId}</p>}
        </fieldset>

        {causa?.requiereDetalle && (
          <Campo
            etiqueta={t('merma.detalle')}
            value={detalle}
            maxLength={200}
            onChange={(e) => { setDetalle(e.target.value); setErrores((x) => ({ ...x, detalle: undefined })) }}
            error={errores.detalle}
          />
        )}

        {aviso && (
          <Alerta tono="info" titulo={t('merma.aviso')} descripcion={t('merma.avisoTexto')} onCerrar={() => setAviso(false)} />
        )}
        {error && <Alerta tono="error" titulo={t('merma.error')} descripcion={mensajeError(error)} />}
        <button type="submit" hidden />
      </form>
    </Dialogo>
  )
}
