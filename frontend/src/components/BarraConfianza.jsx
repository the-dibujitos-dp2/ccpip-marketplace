import { useTranslation } from "react-i18next";
import iconoEscudo from "../assets/icons/escudo-check.svg";
import SelectorIdioma from "./SelectorIdioma.jsx";
import "./BarraConfianza.css";

/** Barra superior de la cabecera web: mensaje de origen verificado y selector de idioma (CCPIP-265). */
export default function BarraConfianza() {
  const { t } = useTranslation();
  return (
    <div className="barra-confianza">
      <img src={iconoEscudo} width="16" height="16" alt="" />
      <p>{t("barraConfianza.mensaje")}</p>
      <SelectorIdioma className="barra-confianza__idioma" />
    </div>
  );
}
