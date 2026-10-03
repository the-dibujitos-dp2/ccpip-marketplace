package pe.ccpip.auth.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import pe.ccpip.auth.model.Rol;

/** SessionStore en memoria para probar la logica de SessionService sin base de datos. */
class InMemorySessionStore implements SessionStore {

    final Map<String, StoredSession> sessions = new HashMap<>();
    final Map<Long, Boolean> activeUsers = new HashMap<>();
    int touches;

    @Override
    public void save(String tokenHash, long userId, Instant now) {
        sessions.put(tokenHash, new StoredSession(tokenHash, userId, Rol.CONSUMIDOR, true, now));
    }

    @Override
    public Optional<StoredSession> find(String tokenHash) {
        return Optional.ofNullable(sessions.get(tokenHash)).map(s -> new StoredSession(
                s.tokenHash(), s.userId(), s.role(), activeUsers.getOrDefault(s.userId(), true), s.lastAccess()));
    }

    @Override
    public void touch(String tokenHash, Instant now) {
        touches++;
        sessions.computeIfPresent(tokenHash, (k, s) ->
                new StoredSession(k, s.userId(), s.role(), s.userActive(), now));
    }

    @Override
    public void delete(String tokenHash) {
        sessions.remove(tokenHash);
    }

    @Override
    public int deleteInactiveSince(Instant threshold) {
        int before = sessions.size();
        sessions.values().removeIf(s -> !s.lastAccess().isAfter(threshold));
        return before - sessions.size();
    }
}
