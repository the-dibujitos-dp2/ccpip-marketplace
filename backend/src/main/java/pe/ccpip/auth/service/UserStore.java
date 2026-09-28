package pe.ccpip.auth.service;

import java.util.Optional;

import pe.ccpip.auth.model.AuthUser;

/**
 * Contrato de acceso a usuarios. Hoy lo implementa {@link InMemoryUserStore};
 * cuando exista la base de datos (CCPIP-19) se implementa contra PostgreSQL
 * sin cambiar controllers ni filtros.
 */
public interface UserStore {

    Optional<AuthUser> findByEmail(String email);

    Optional<AuthUser> findById(String id);
}
