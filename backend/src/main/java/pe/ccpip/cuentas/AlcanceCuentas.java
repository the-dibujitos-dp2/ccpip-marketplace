package pe.ccpip.cuentas;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import pe.ccpip.auth.model.Rol;

/**
 * CCPIP-95: que cuentas ve y gestiona cada rol administrativo. Se aplica en el servidor (RNF-05):
 * una cuenta fuera del alcance responde como inexistente.
 *
 * <ul>
 *   <li>El superadministrador ve y suspende todas las cuentas menos las de otros superadministradores, y es
 *   el unico que crea, modifica y desactiva cuentas de sub administrador y de administrador de punto.</li>
 *   <li>El sub administrador ve y suspende las cuentas de la operacion: consumidores, productores y
 *   distribuidores. No ve cuentas administrativas.</li>
 * </ul>
 * Nadie gestiona su propia cuenta ni una de su mismo nivel o superior, asi nadie se da mas privilegios.
 */
final class AlcanceCuentas {

    /** Roles cuyas cuentas crea, modifica y desactiva el superadministrador (CCPIP-301 a 306). */
    static final Set<Rol> ADMINISTRATIVOS = EnumSet.of(Rol.SUB_ADMIN, Rol.ADMIN_PUNTO);

    private static final Set<Rol> OPERACION = EnumSet.of(Rol.CONSUMIDOR, Rol.PRODUCTOR, Rol.DISTRIBUIDOR);

    private AlcanceCuentas() {
    }

    /** Roles cuyas cuentas ve, busca, suspende y reactiva {@code actor}. Vacio si no administra cuentas. */
    static Set<Rol> visibles(Rol actor) {
        if (actor == Rol.SUPER_ADMIN) {
            EnumSet<Rol> roles = EnumSet.copyOf(OPERACION);
            roles.addAll(ADMINISTRATIVOS);
            return roles;
        }
        if (actor == Rol.SUB_ADMIN) {
            return EnumSet.copyOf(OPERACION);
        }
        return EnumSet.noneOf(Rol.class);
    }

    /** Lo mismo, como lista en el orden del catalogo de roles (EnumSet recorre en ese orden). */
    static List<Rol> listaVisibles(Rol actor) {
        return List.copyOf(visibles(actor));
    }

    /** Si {@code actor} crea, modifica y desactiva cuentas con rol {@code rol}. */
    static boolean administra(Rol actor, Rol rol) {
        return actor == Rol.SUPER_ADMIN && ADMINISTRATIVOS.contains(rol);
    }
}
