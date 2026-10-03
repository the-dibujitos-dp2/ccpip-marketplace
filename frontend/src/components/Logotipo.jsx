import iconoHoja from "../assets/icons/hoja.svg";
import "./Logotipo.css";

/** Marca de trabajo del prototipo. Se reemplaza cuando la CCPIP entregue su manual de marca. */
export default function Logotipo() {
  return (
    <div className="logotipo">
      <span className="logotipo__isotipo">
        <img src={iconoHoja} width="20" height="20" alt="" />
      </span>
      <span className="logotipo__texto">
        <span className="logotipo__nombre">Mercado CCPIP</span>
        <span className="logotipo__lema">Comercio de pueblos indígenas</span>
      </span>
    </div>
  );
}
