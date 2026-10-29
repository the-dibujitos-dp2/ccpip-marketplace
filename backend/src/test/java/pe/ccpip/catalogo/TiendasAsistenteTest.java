package pe.ccpip.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogo.CatalogoModelos.TiendaTarjeta;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-347: que tiendas puede mostrar el asistente. Solo publicadas, por terminos (nombre, comunidad, pueblo
 * o localidad), por region o por los productos citados, en orden alfabetico y hasta el limite.
 */
@Transactional
class TiendasAsistenteTest extends IntegrationTest {

    @Autowired
    private TiendasRepository tiendas;

    private static List<String> nombres(List<TiendaTarjeta> t) {
        return t.stream().map(TiendaTarjeta::nombre).toList();
    }

    @Test
    void sinCriteriosNoDevuelveNinguna() {
        assertThat(tiendas.paraAsistente(List.of(), List.of(), List.of(), 6)).isEmpty();
    }

    @Test
    void porTerminoSinDistinguirMayusculasNiTildes() {
        assertThat(tiendas.paraAsistente(List.of("CHINCHÉRO"), List.of(), List.of(), 6))
                .extracting(TiendaTarjeta::comunidad).containsExactly("Comunidad de Tejedoras de Chinchero");
        // Tambien por el pueblo
        assertThat(tiendas.paraAsistente(List.of("quechua"), List.of(), List.of(), 6)).isNotEmpty();
    }

    @Test
    void porRegionEnOrdenAlfabeticoYHastaElLimite() {
        String region = jdbc.queryForObject("""
                SELECT region FROM productor WHERE activo AND tienda_publicada
                GROUP BY region ORDER BY count(*) DESC, region LIMIT 1""", String.class);
        List<TiendaTarjeta> todas = tiendas.paraAsistente(List.of(), List.of(region), List.of(), 20);
        assertThat(todas).isNotEmpty().allMatch(t -> region.equals(t.region()));
        assertThat(nombres(todas)).isSorted();
        assertThat(tiendas.paraAsistente(List.of(), List.of(region), List.of(), 1)).hasSize(1);
    }

    @Test
    void porLosProductosCitados() {
        long producto = jdbc.queryForObject("""
                SELECT p.id FROM producto p JOIN productor pr ON pr.id = p.productor_id AND pr.activo AND pr.tienda_publicada
                WHERE p.estado = 'PUBLICADO' ORDER BY p.id LIMIT 1""", Long.class);
        long tienda = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, producto);
        assertThat(tiendas.paraAsistente(List.of(), List.of(), List.of(producto), 6))
                .extracting(TiendaTarjeta::id).containsExactly(tienda);
    }

    @Test
    void noDevuelveTiendasNoPublicadasNiInactivas() {
        jdbc.update("UPDATE productor SET activo = false WHERE comunidad = 'Comunidad de Tejedoras de Chinchero'");
        assertThat(tiendas.paraAsistente(List.of("chinchero"), List.of(), List.of(), 6)).isEmpty();
    }
}
