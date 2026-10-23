-- Catalogo territorial PROVISIONAL (CCPIP-59). Solo los distritos de las zonas donde hoy hay puntos de
-- acopio, con su codigo de ubigeo del INEI. El catalogo completo y aprobado lo carga CCPIP-149 y
-- reemplaza este archivo. Idempotente (RNF-28): no repite ni pisa lo ya cargado.

INSERT INTO ubigeo (codigo, departamento, provincia, distrito) VALUES
    ('150101', 'Lima', 'Lima', 'Lima'),
    ('150104', 'Lima', 'Lima', 'Barranco'),
    ('150113', 'Lima', 'Lima', 'Jesús María'),
    ('150114', 'Lima', 'Lima', 'La Molina'),
    ('150122', 'Lima', 'Lima', 'Miraflores'),
    ('150130', 'Lima', 'Lima', 'San Borja'),
    ('150131', 'Lima', 'Lima', 'San Isidro'),
    ('150140', 'Lima', 'Lima', 'Santiago de Surco'),
    ('150141', 'Lima', 'Lima', 'Surquillo'),
    ('080101', 'Cusco', 'Cusco', 'Cusco'),
    ('080104', 'Cusco', 'Cusco', 'San Jerónimo'),
    ('080105', 'Cusco', 'Cusco', 'San Sebastián'),
    ('080106', 'Cusco', 'Cusco', 'Santiago'),
    ('080108', 'Cusco', 'Cusco', 'Wanchaq'),
    ('160101', 'Loreto', 'Maynas', 'Iquitos'),
    ('160107', 'Loreto', 'Maynas', 'Punchana'),
    ('160113', 'Loreto', 'Maynas', 'Belén'),
    ('160114', 'Loreto', 'Maynas', 'San Juan Bautista'),
    ('250101', 'Ucayali', 'Coronel Portillo', 'Callería'),
    ('250105', 'Ucayali', 'Coronel Portillo', 'Yarinacocha'),
    ('250107', 'Ucayali', 'Coronel Portillo', 'Manantay'),
    ('220101', 'San Martín', 'Moyobamba', 'Moyobamba'),
    ('220901', 'San Martín', 'San Martín', 'Tarapoto'),
    ('220909', 'San Martín', 'San Martín', 'La Banda de Shilcayo'),
    ('220910', 'San Martín', 'San Martín', 'Morales'),
    ('120301', 'Junín', 'Chanchamayo', 'Chanchamayo'),
    ('120305', 'Junín', 'Chanchamayo', 'San Ramón')
ON CONFLICT (codigo) DO NOTHING;
