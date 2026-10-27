package pe.ccpip.distribucion;

import pe.ccpip.distribucion.DistribucionModelos.Origen;
import pe.ccpip.entrega.EntregaModelos.Distrito;

/** CCPIP-110: solo ubicaciones registradas de una entrega propia ENVIO; no incluye datos de contacto. */
public final class DistribucionUbicacionesModelos {
    private DistribucionUbicacionesModelos() { }

    /** Nombres JSON: origen (incluida direccion), destino (Distrito + direccion y referencia registradas). */
    public record UbicacionesPedido(String numero, Origen origen, UbicacionDestino destino) { }

    public record UbicacionDestino(Distrito territorio, String direccion, String referencia) { }
}
