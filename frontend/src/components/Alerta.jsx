import { useTranslation } from 'react-i18next'
import iconoInfo from "../assets/icons/info.svg";
import iconoExito from "../assets/icons/check-circulo.svg";
import iconoAviso from "../assets/icons/alerta-aviso.svg";
import iconoError from "../assets/icons/alerta-error.svg";
import iconoCerrar from "../assets/icons/cerrar.svg";
import "./Alerta.css";

const ICONOS = { info: iconoInfo, exito: iconoExito, aviso: iconoAviso, error: iconoError };

/** Alerta persistente dentro del contenido. tono: info | exito | aviso | error */
export default function Alerta({ tono = "info", titulo, descripcion, onCerrar }) {
  const { t } = useTranslation()
  const urgente = tono === "error" || tono === "aviso";
  return (
    <div className={`alerta alerta--${tono}`} role={urgente ? "alert" : "status"}>
      <img src={ICONOS[tono]} width="20" height="20" alt="" />
      <div className="alerta__texto">
        <p className="alerta__titulo">{titulo}</p>
        {descripcion && <p className="alerta__descripcion">{descripcion}</p>}
      </div>
      {onCerrar && (
        <button type="button" className="alerta__cerrar" onClick={onCerrar} aria-label={t('comun.cerrarAviso')}>
          <img src={iconoCerrar} width="18" height="18" alt="" />
        </button>
      )}
    </div>
  );
}
