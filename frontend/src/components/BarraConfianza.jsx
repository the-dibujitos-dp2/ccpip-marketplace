import { useTranslation } from "react-i18next";
import iconoEscudo from "../assets/icons/escudo-check.svg";
import "./BarraConfianza.css";

/** Barra superior de la cabecera web: mensaje de origen verificado. */
export default function BarraConfianza() {
  const { t } = useTranslation();
  return (
    <div className="barra-confianza">
      <img src={iconoEscudo} width="16" height="16" alt="" />
      <p>{t("barraConfianza.mensaje")}</p>
    </div>
  );
}
