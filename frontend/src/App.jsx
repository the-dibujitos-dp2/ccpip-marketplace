import { useEffect, useState } from "react";
import { fetchMe, login, logout } from "./api/auth.js";

export default function App() {
    const [user, setUser] = useState(null);
    const [loading, setLoading] = useState(true);
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [message, setMessage] = useState("");

    // Si ya habia un token guardado, recuperar la sesion al recargar
    useEffect(() => {
        fetchMe()
            .then(setUser)
            .catch(() => setUser(null))
            .finally(() => setLoading(false));
    }, []);

    async function handleLogin(e) {
        e.preventDefault();
        setMessage("");
        try {
            setUser(await login(email, password));
            setPassword("");
        } catch (err) {
            setMessage(err.message);
        }
    }

    async function handleLogout() {
        await logout();
        setUser(null);
        setMessage("Sesión cerrada.");
    }

    if (loading) return <p>Cargando...</p>;

    if (user) {
        return (
            <main>
                <h1>Hola, {user.fullName}</h1>
                <p>Correo: {user.email}</p>
                <p>Rol: {user.role}</p>
                <button onClick={handleLogout}>Cerrar sesión</button>
            </main>
        );
    }

    return (
        <main>
            <h1>Iniciar sesión</h1>
            <form onSubmit={handleLogin}>
                <p>
                    <label>
                        Correo{" "}
                        <input
                            type="email"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            required
                        />
                    </label>
                </p>
                <p>
                    <label>
                        Contraseña{" "}
                        <input
                            type="password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            required
                        />
                    </label>
                </p>
                <button type="submit">Ingresar</button>
            </form>
            {message && <p>{message}</p>}
        </main>
    );
}
