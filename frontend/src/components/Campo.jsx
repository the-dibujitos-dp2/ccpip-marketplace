import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../assets/icons/alerta-campo.svg'
import iconoChevron from '../assets/icons/chevron-abajo.svg'
import iconoOjo from '../assets/icons/ojo.svg'
import iconoOjoTachado from '../assets/icons/ojo-tachado.svg'
import Icono from './Icono.jsx'
import './Campo.css'

/**
 * Campo de formulario con etiqueta persistente. Error = borde rojo 2px + icono + mensaje.
 * tipo: texto (por defecto) | area | selector | contrasena. Para selector, `opciones`: [{ valor, etiqueta }];
 * con `placeholder={false}` no ofrece la opcion vacia (cuando siempre hay una elegida).
 * contrasena: campo oculto con un boton de ojo para mostrar u ocultar lo escrito.
 */
export default function Campo({ etiqueta, ayuda, error, tipo = 'texto', opciones = [], placeholder, className = '', id: idPropio, ...props }) {
  const { t } = useTranslation()
  const generado = useId()
  const [visible, setVisible] = useState(false)
  const id = idPropio ?? generado
  const idAyuda = `${id}-ayuda`
  const mensaje = error || ayuda
  const control = {
    id,
    className: `campo__control${error ? ' campo__control--error' : ''}${tipo === 'area' ? ' campo__control--area' : ''}`,
    'aria-invalid': error ? 'true' : undefined,
    'aria-describedby': mensaje ? idAyuda : undefined,
    ...props,
  }
  return (
    <div className={`campo ${className}`}>
      <label className="campo__etiqueta" htmlFor={id}>
        {etiqueta}
      </label>
      {tipo === 'area' && <textarea rows={4} placeholder={placeholder} {...control} />}
      {tipo === 'selector' && (
        <div className="campo__selector">
          <select {...control}>
            {placeholder !== false && <option value="">{placeholder ?? t('campo.elegir')}</option>}
            {opciones.map((o) => (
              <option key={o.valor} value={o.valor}>
                {o.etiqueta}
              </option>
            ))}
          </select>
          <Icono src={iconoChevron} tamano={20} className="campo__chevron" />
        </div>
      )}
      {tipo === 'texto' && <input placeholder={placeholder} {...control} />}
      {tipo === 'contrasena' && (
        <div className="campo__con-accion">
          <input placeholder={placeholder} {...control} type={visible ? 'text' : 'password'} />
          <button
            type="button"
            className="campo__accion"
            onClick={() => setVisible((v) => !v)}
            aria-label={visible ? t('campo.ocultarContrasena') : t('campo.mostrarContrasena')}
            aria-pressed={visible}
            aria-controls={id}
          >
            <Icono src={visible ? iconoOjoTachado : iconoOjo} tamano={20} />
          </button>
        </div>
      )}
      {mensaje && (
        <p id={idAyuda} className={`campo__ayuda${error ? ' campo__ayuda--error' : ''}`}>
          {error && <img src={iconoAlerta} width="16" height="16" alt="" />}
          {mensaje}
        </p>
      )}
    </div>
  )
}
