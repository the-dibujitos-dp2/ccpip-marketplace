import iconoEscudo from "../assets/icons/escudo-check.svg";
import "./BarraConfianza.css";

/** Barra superior de la cabecera web: mensaje de origen verificado. */
export default function BarraConfianza() {
  return (
    <div className="barra-confianza">
      <img src={iconoEscudo} width="16" height="16" alt="" />
      <p>Productos con origen verificado por la Cámara de Comercio de los Pueblos Indígenas del Perú</p>
    </div>
  );
}
