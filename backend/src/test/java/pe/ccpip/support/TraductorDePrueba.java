package pe.ccpip.support;

import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import pe.ccpip.comun.Idioma;
import pe.ccpip.traduccion.TraductorAutomatico;
import pe.ccpip.traduccion.TraductorNoConfigurado;
import pe.ccpip.traduccion.TraductorSimulado;

/**
 * CCPIP-147: en las pruebas la traduccion automatica usa el proveedor simulado; con
 * {@link Conmutable#sinProveedor(boolean)} se comporta como el no configurado (503), sin levantar
 * otro contexto de Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TraductorDePrueba {

    public static final class Conmutable implements TraductorAutomatico {

        private final TraductorAutomatico simulado = new TraductorSimulado();
        private final TraductorAutomatico ninguno = new TraductorNoConfigurado();
        private volatile boolean sinProveedor;

        private TraductorAutomatico actual() {
            return sinProveedor ? ninguno : simulado;
        }

        @Override
        public Map<String, String> traducir(Map<String, String> textos, Idioma origen, Idioma destino) {
            return actual().traducir(textos, origen, destino);
        }

        @Override
        public String proveedor() {
            return actual().proveedor();
        }

        @Override
        public boolean disponible() {
            return actual().disponible();
        }

        public void sinProveedor(boolean sinProveedor) {
            this.sinProveedor = sinProveedor;
        }
    }

    @Bean
    @Primary
    Conmutable traductorDePrueba() {
        return new Conmutable();
    }
}
