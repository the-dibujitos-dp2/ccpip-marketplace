package pe.ccpip.distribucion;

import java.time.Instant;

import pe.ccpip.entrega.EntregaModelos.Distrito;

/** Respuestas de la distribucion de pedidos a domicilio al distribuidor (CCPIP-107). */
public final class DistribucionModelos {

    private DistribucionModelos() {
    }

    /** El punto de acopio donde el distribuidor recoge el pedido. Sin coordenadas: los mapas son simulados (RNF-31). */
    public record Origen(long id, String nombre, String distrito, String direccion) {
    }

    /**
     * Un pedido preparado y disponible para distribuir. {@code destino} llega solo hasta el distrito: la direccion
     * y el contacto de quien recibe se conocen al asumir la entrega (CCPIP-108, CCPIP-110), y el comprador y su
     * celular nunca se muestran aqui (RNF-05). {@code disponibleDesde} es cuando quedo listo; {@code esperaMinutos},
     * lo que lleva esperando.
     */
    public record PedidoDisponible(String numero, Origen origen, Distrito destino, Instant disponibleDesde,
                                   long esperaMinutos) {
    }
}
