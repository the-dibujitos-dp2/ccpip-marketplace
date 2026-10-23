-- CCPIP-59: cobertura de entrega de ejemplo para la demo. Cada punto de acopio atiende distritos de su
-- zona. Los distritos reales los declara cada punto (CCPIP-149). Idempotente (RNF-28): no repite ni
-- pisa lo que un punto ya haya declarado.

INSERT INTO cobertura_entrega (ubicacion_id, ubigeo)
SELECT u.id, c.ubigeo
FROM ubicacion u
JOIN (VALUES
    ('Centro Nacional Lima', '150101'), ('Centro Nacional Lima', '150104'), ('Centro Nacional Lima', '150113'),
    ('Centro Nacional Lima', '150114'), ('Centro Nacional Lima', '150122'), ('Centro Nacional Lima', '150130'),
    ('Centro Nacional Lima', '150131'), ('Centro Nacional Lima', '150140'), ('Centro Nacional Lima', '150141'),
    ('Centro Regional Cusco', '080101'), ('Centro Regional Cusco', '080104'), ('Centro Regional Cusco', '080105'),
    ('Centro Regional Cusco', '080106'), ('Centro Regional Cusco', '080108'),
    ('Centro Regional Loreto', '160101'), ('Centro Regional Loreto', '160107'), ('Centro Regional Loreto', '160113'),
    ('Centro Regional Loreto', '160114'),
    ('Centro Regional Ucayali', '250101'), ('Centro Regional Ucayali', '250105'), ('Centro Regional Ucayali', '250107'),
    ('Centro Regional San Martin', '220101'), ('Centro Regional San Martin', '220901'),
    ('Centro Regional San Martin', '220909'), ('Centro Regional San Martin', '220910'),
    ('Centro Regional Junin', '120301'), ('Centro Regional Junin', '120305')
) AS c(punto, ubigeo) ON c.punto = u.nombre
WHERE u.tipo = 'PUNTO_ACOPIO'
ON CONFLICT (ubicacion_id, ubigeo) DO NOTHING;
