import "./Boton.css";

/**
 * Boton del sistema Monte. Primario = accion principal (max. 1 por vista).
 * tipo: primario | secundario | terciario | acento | destructivo · tamano: L | M | S
 */
export default function Boton({ tipo = "primario", tamano = "L", anchoCompleto = false, children, ...props }) {
  const clases = ["boton", `boton--${tipo}`, `boton--${tamano}`, anchoCompleto && "boton--ancho"]
    .filter(Boolean)
    .join(" ");
  return (
    <button className={clases} {...props}>
      {children}
    </button>
  );
}
