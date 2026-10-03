import { useId } from "react";
import iconoAlerta from "../assets/icons/alerta-campo.svg";
import "./Campo.css";

/** Campo de formulario con etiqueta persistente. Error = borde rojo 2px + icono + mensaje. */
export default function Campo({ etiqueta, ayuda, error, ...inputProps }) {
  const id = useId();
  const idAyuda = `${id}-ayuda`;
  const mensaje = error || ayuda;
  return (
    <div className="campo">
      <label className="campo__etiqueta" htmlFor={id}>
        {etiqueta}
      </label>
      <input
        id={id}
        className={`campo__control${error ? " campo__control--error" : ""}`}
        aria-invalid={error ? "true" : undefined}
        aria-describedby={mensaje ? idAyuda : undefined}
        {...inputProps}
      />
      {mensaje && (
        <p id={idAyuda} className={`campo__ayuda${error ? " campo__ayuda--error" : ""}`}>
          {error && <img src={iconoAlerta} width="16" height="16" alt="" />}
          {mensaje}
        </p>
      )}
    </div>
  );
}
