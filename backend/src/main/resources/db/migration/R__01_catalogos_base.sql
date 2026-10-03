-- Datos sembrados, parte 1: catalogos cerrados y puntos de acopio
-- CCPIP-24. Idempotente, segun RNF-28: se puede volver a ejecutar sin duplicar.
--
-- Va en db/migration y corre en TODOS los ambientes: sin roles, catalogos y puntos
-- de acopio la aplicacion no funciona. Los datos de demostracion (R__02) van en
-- db/seed y solo se cargan en desarrollo.

INSERT INTO rol (id, codigo, nombre) VALUES
    (1, 'CONSUMIDOR',        'Consumidor final'),
    (2, 'PRODUCTOR',         'Productor'),
    (3, 'ADMIN_PUNTO',       'Administrador de punto de acopio'),
    (4, 'SUB_ADMIN',         'Sub administrador'),
    (5, 'SUPER_ADMIN',       'Super administrador'),
    (6, 'DISTRIBUIDOR',      'Distribuidor')
ON CONFLICT (id) DO NOTHING;

INSERT INTO linea_producto (codigo, nombre) VALUES
    ('ACEITES',     'Aceites'),
    ('SUPERFRUTOS', 'Superfrutos'),
    ('CAFE',        'Cafe'),
    ('CACAO',       'Cacao'),
    ('INFUSIONES',  'Infusiones'),
    ('ARTESANIA',   'Artesania'),
    ('TEXTIL',      'Textiles')
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO unidad_medida (codigo, nombre) VALUES
    ('UNIDAD', 'Unidad'),
    ('KG',     'Kilogramo'),
    ('G',      'Gramo'),
    ('L',      'Litro'),
    ('ML',     'Mililitro'),
    ('SACO',   'Saco'),
    ('JABA',   'Jaba')
ON CONFLICT (codigo) DO NOTHING;

-- CCPIP-24: los cinco centros regionales y el centro nacional de Lima.
INSERT INTO ubicacion (tipo, nombre, departamento, provincia, distrito, direccion) VALUES
    ('PUNTO_ACOPIO', 'Centro Nacional Lima',  'Lima',       'Lima',       'Lima',        'Sede central CCPIP'),
    ('PUNTO_ACOPIO', 'Centro Regional Loreto','Loreto',     'Maynas',     'Iquitos',     'Centro regional amazonico'),
    ('PUNTO_ACOPIO', 'Centro Regional Cusco', 'Cusco',      'Cusco',      'Cusco',       'Centro regional andino'),
    ('PUNTO_ACOPIO', 'Centro Regional Ucayali','Ucayali',   'Coronel Portillo', 'Pucallpa','Centro regional selva central'),
    ('PUNTO_ACOPIO', 'Centro Regional San Martin','San Martin','Moyobamba','Moyobamba',  'Centro regional nororiental'),
    ('PUNTO_ACOPIO', 'Centro Regional Junin', 'Junin',      'Chanchamayo','La Merced',   'Centro regional ceja de selva')
ON CONFLICT DO NOTHING;
