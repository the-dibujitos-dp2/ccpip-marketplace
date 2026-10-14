-- Datos sembrados, parte 1: catalogos cerrados y puntos de acopio
-- CCPIP-24. Idempotente, segun RNF-28: se puede volver a ejecutar sin duplicar.
--
-- Va en db/migration y corre en TODOS los ambientes: sin roles, catalogos y puntos
-- de acopio la aplicacion no funciona. Los datos de demostracion (R__02) van en
-- db/seed, que hoy tambien corre en todos los ambientes (ver application.properties).

INSERT INTO rol (id, codigo, nombre) VALUES
    (1, 'CONSUMIDOR',        'Consumidor final'),
    (2, 'PRODUCTOR',         'Productor'),
    (3, 'ADMIN_PUNTO',       'Administrador de punto de acopio'),
    (4, 'SUB_ADMIN',         'Sub administrador'),
    (5, 'SUPER_ADMIN',       'Super administrador'),
    (6, 'DISTRIBUIDOR',      'Distribuidor')
ON CONFLICT (id) DO NOTHING;

-- Unidades del catalogo cerrado (RNF-32). Las presentaciones (botella, bolsa...) se
-- agregaron con PW-04, donde el productor elige "Botella" y aparte escribe "250 ml".
INSERT INTO unidad_medida (codigo, nombre) VALUES
    ('UNIDAD',  'Unidad'),
    ('KG',      'Kilogramo'),
    ('G',       'Gramo'),
    ('L',       'Litro'),
    ('ML',      'Mililitro'),
    ('SACO',    'Saco'),
    ('JABA',    'Jaba'),
    ('BOTELLA', 'Botella'),
    ('FRASCO',  'Frasco'),
    ('BOLSA',   'Bolsa'),
    ('PAQUETE', 'Paquete'),
    ('CAJA',    'Caja'),
    ('SOBRE',   'Sobre')
ON CONFLICT (codigo) DO NOTHING;

-- CCPIP-45: cada linea sugiere su unidad y decide si el lote vence. Las artesanias y los
-- textiles no vencen. Nombres y orden segun el prototipo (CW-01, CW-02).
INSERT INTO linea_producto (codigo, nombre, requiere_vencimiento, unidad_sugerida_id, orden)
SELECT d.codigo, d.nombre, d.vence, u.id, d.orden
FROM (VALUES
    ('CACAO',       'Cacao y chocolates', true,  'BOLSA',   1),
    ('CAFE',        'Café',               true,  'BOLSA',   2),
    ('SUPERFRUTOS', 'Superfrutos',        true,  'BOLSA',   3),
    ('ACEITES',     'Aceites',            true,  'BOTELLA', 4),
    ('INFUSIONES',  'Infusiones',         true,  'SOBRE',   5),
    ('ARTESANIA',   'Artesanías',         false, 'UNIDAD',  6),
    ('TEXTIL',      'Textiles',           false, 'UNIDAD',  7)
) AS d(codigo, nombre, vence, unidad, orden)
JOIN unidad_medida u ON u.codigo = d.unidad
ON CONFLICT (codigo) DO UPDATE SET
    nombre = EXCLUDED.nombre,
    requiere_vencimiento = EXCLUDED.requiere_vencimiento,
    unidad_sugerida_id = EXCLUDED.unidad_sugerida_id,
    orden = EXCLUDED.orden;

-- CCPIP-24: los cinco centros regionales y el centro nacional de Lima.
INSERT INTO ubicacion (tipo, nombre, departamento, provincia, distrito, direccion) VALUES
    ('PUNTO_ACOPIO', 'Centro Nacional Lima',  'Lima',       'Lima',       'Lima',        'Sede central CCPIP'),
    ('PUNTO_ACOPIO', 'Centro Regional Loreto','Loreto',     'Maynas',     'Iquitos',     'Centro regional amazonico'),
    ('PUNTO_ACOPIO', 'Centro Regional Cusco', 'Cusco',      'Cusco',      'Cusco',       'Centro regional andino'),
    ('PUNTO_ACOPIO', 'Centro Regional Ucayali','Ucayali',   'Coronel Portillo', 'Pucallpa','Centro regional selva central'),
    ('PUNTO_ACOPIO', 'Centro Regional San Martin','San Martin','Moyobamba','Moyobamba',  'Centro regional nororiental'),
    ('PUNTO_ACOPIO', 'Centro Regional Junin', 'Junin',      'Chanchamayo','La Merced',   'Centro regional ceja de selva')
ON CONFLICT DO NOTHING;
