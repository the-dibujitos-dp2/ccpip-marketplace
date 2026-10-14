-- Datos sembrados, parte 2: cuentas, productores, productos y stock inicial
-- CCPIP-150. Idempotente, segun RNF-28.
-- Corre en todos los ambientes: CCPIP-23 pide un usuario de demostracion por rol para
-- las revisiones de sprint, y la prueba de humo del despliegue inicia sesion con
-- consumidor@ccpip.test. Ver docs/devsecops.md.
--
-- Las contrasenas van como hash BCrypt, no en claro (RNF-01). Todas las cuentas de
-- demostracion usan la misma clave, publica a proposito: Demo1234!

INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado) VALUES
    ('consumidor@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Consumidor de Prueba',              1, true),
    ('productor@ccpip.test',    '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Shipibo',                 2, true),
    ('productor2@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Awajun',                  2, true),
    ('productor3@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Asháninka',               2, true),
    ('admin_acopio@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio de Prueba', 3, true),
    ('sub_admin@ccpip.test',    '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Sub-Administrador de Prueba',       4, true),
    ('sudo@ccpip.test',         '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Super Administrador de Prueba',     5, true),
    ('distribuidor@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Distribuidor de Prueba',            6, true),
    -- CCPIP-27: cuenta sin confirmar para probar que el acceso se rechaza
    ('sin_confirmar@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Consumidor sin Confirmar',          1, false),
    -- Productor incorporado por la camara pero con la tienda aun sin armar: prueba el flujo
    -- completo de crear la tienda y publicar el primer producto (PW-08, PW-04)
    ('productor_nuevo@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Rosa Ancón',                       2, true)
ON CONFLICT (correo) DO NOTHING;

-- El administrador de punto queda ligado al centro nacional (RNF-05).
INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
SELECT u.id, l.id
FROM usuario u, ubicacion l
WHERE u.correo = 'admin_acopio@ccpip.test'
  AND l.nombre = 'Centro Nacional Lima'
ON CONFLICT DO NOTHING;

-- Tres productores con su comunidad, region, historia y origen verificado.
INSERT INTO productor (usuario_id, comunidad, region, historia, origen_verificado)
SELECT u.id, d.comunidad, d.region, d.historia, true
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Comunidad Shipibo Konibo de Yarinacocha', 'Ucayali',
     'Trabajamos el aceite de ungurahui desde hace cuatro generaciones. La cosecha se hace a mano, subiendo la palmera sin cortarla, para que siga dando fruto el ano siguiente.'),
    ('productor2@ccpip.test', 'Comunidad Awajun del Alto Maranon', 'Amazonas',
     'Nuestro cacao crece bajo la sombra del bosque, sin tumbar un solo arbol. Lo fermentamos en cajones de madera durante seis dias, como nos enseno la abuela.'),
    ('productor3@ccpip.test', 'Comunidad Asháninka de Chanchamayo', 'Junin',
     'Sembramos cafe a mil ochocientos metros, entre arboles nativos que dan sombra al grano. Cada saco lleva el nombre de la familia que lo cosecho.')
) AS d(correo, comunidad, region, historia) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

-- Diez productos repartidos entre los tres productores, en estado PUBLICADO.
INSERT INTO producto (productor_id, nombre, descripcion, historia_origen, linea_id, unidad_medida_id, precio, es_materia_prima, estado)
SELECT p.id, d.nombre, d.descripcion, d.historia, l.id, m.id, d.precio, false, 'PUBLICADO'
FROM (VALUES
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Aceite de ungurahui 250 ml',   'Aceite prensado en frio, sin refinar.',        'Cosechado a mano en la cuenca del Ucayali, sin talar la palmera.', 'ACEITES',     'ML',     48.00),
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Aceite de aguaje 250 ml',      'Rico en vitamina A, prensado en frio.',        'El aguaje se recolecta del suelo, nunca cortando el arbol.',       'ACEITES',     'ML',     52.00),
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Camu camu en polvo 150 g',     'Deshidratado a baja temperatura.',             'Crece silvestre en las orillas inundables del rio.',              'SUPERFRUTOS', 'G',      39.00),
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Textil bordado shipibo',       'Bordado a mano con diseno kene.',              'Cada kene es un canto traducido en lineas. No se repite ninguno.', 'TEXTIL',      'UNIDAD', 180.00),
    ('Comunidad Awajun del Alto Maranon',       'Cacao en grano fermentado 1 kg','Fermentado seis dias en cajones de madera.',   'Crece bajo sombra de bosque primario, sin deforestar.',            'CACAO',       'KG',     42.00),
    ('Comunidad Awajun del Alto Maranon',       'Pasta de cacao 500 g',         'Cien por ciento cacao, sin azucar anadida.',    'Molido en piedra, como se hizo siempre en la comunidad.',          'CACAO',       'G',      55.00),
    ('Comunidad Awajun del Alto Maranon',       'Infusion de guayusa 80 g',     'Hoja secada al sol.',                          'Se toma antes del amanecer, en circulo, para contar los suenos.',  'INFUSIONES',  'G',      28.00),
    ('Comunidad Asháninka de Chanchamayo',      'Cafe tostado en grano 500 g',  'Tueste medio, notas de panela y citricos.',     'Sembrado a mil ochocientos metros entre arboles nativos.',         'CAFE',        'G',      46.00),
    ('Comunidad Asháninka de Chanchamayo',      'Cafe molido 250 g',            'Molienda media, para filtro.',                  'Del mismo lote que el grano, molido al momento de empacar.',       'CAFE',        'G',      26.00),
    ('Comunidad Asháninka de Chanchamayo',      'Canasta tejida de tamshi',     'Tejida con fibra de tamshi del bosque.',        'La fibra se recoge sin matar la liana, para que vuelva a crecer.',  'ARTESANIA',   'UNIDAD', 95.00)
) AS d(comunidad, nombre, descripcion, historia, linea, unidad, precio)
JOIN productor p ON p.comunidad = d.comunidad
JOIN linea_producto l ON l.codigo = d.linea
JOIN unidad_medida m ON m.codigo = d.unidad
WHERE NOT EXISTS (
    SELECT 1 FROM producto x WHERE x.productor_id = p.id AND x.nombre = d.nombre
);

-- ---------------------------------------------------------------------------------------
-- Tienda, almacen, lotes, lineas e historia (CCPIP-30, CCPIP-41, CCPIP-45, CCPIP-49).
-- Agregado con el flujo del productor. Todo es idempotente y nunca pisa lo que el
-- productor ya edito desde la plataforma.
-- ---------------------------------------------------------------------------------------

INSERT INTO productor (usuario_id, comunidad, region, origen_verificado, verificado_en)
SELECT u.id, 'Comunidad Kukama de Nauta', 'Loreto', true, current_date
FROM usuario u
WHERE u.correo = 'productor_nuevo@ccpip.test'
  AND NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

UPDATE productor p SET
    nombre_tienda = d.tienda,
    descripcion_corta = d.descripcion,
    cita = d.cita,
    pueblo = d.pueblo,
    localidad = d.localidad,
    socios = d.socios,
    verificado_en = coalesce(p.verificado_en, current_date - 120),
    tienda_publicada = true,
    tienda_publicada_en = coalesce(p.tienda_publicada_en, now())
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Asoc. Nii Joi', 'Aceites, superfrutos y textiles de mujeres shipibo de Yarinacocha.',
     'Las plantas del monte curan y alimentan.', 'Shipibo-Konibo', 'Yarinacocha', 34),
    ('productor2@ccpip.test', 'C.N. Yarina', 'Cacao fino de aroma e infusiones del Alto Marañón.',
     'Sembramos cacao donde antes hubo tala.', 'Awajún', 'Alto Marañón', 20),
    ('productor3@ccpip.test', 'Coop. Río Ene', 'Café de altura y artesanías asháninka de Chanchamayo.',
     'Nuestro café crece bajo sombra de bosque.', 'Asháninka', 'Chanchamayo', 45)
) AS d(correo, tienda, descripcion, cita, pueblo, localidad, socios) ON d.correo = u.correo
WHERE p.usuario_id = u.id AND p.nombre_tienda IS NULL;

-- Cada productor tiene su almacen, desde donde luego transfiere a un punto de acopio
INSERT INTO ubicacion (tipo, nombre, productor_id, departamento, distrito, direccion)
SELECT 'ALMACEN_PRODUCTOR', 'Almacén ' || p.comunidad, p.id, p.region, p.localidad, 'Almacén de la comunidad'
FROM productor p
WHERE NOT EXISTS (
    SELECT 1 FROM ubicacion x WHERE x.productor_id = p.id AND x.tipo = 'ALMACEN_PRODUCTOR');

-- Los productos sembrados ya estan publicados y en su linea
UPDATE producto SET publicado_en = creado_en WHERE estado = 'PUBLICADO' AND publicado_en IS NULL;

INSERT INTO producto_linea (producto_id, linea_id)
SELECT id, linea_id FROM producto WHERE linea_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- La historia sembrada cuenta como escrita y aprobada por la comunidad (CCPIP-49).
-- Solo la de los productos sembrados: la de un producto creado en la plataforma la
-- aprueba la comunidad, no esta semilla.
INSERT INTO producto_historia (producto_id, texto_final, aprobada_en)
SELECT pr.id, pr.historia_origen, now() FROM producto pr
WHERE pr.historia_origen IS NOT NULL AND btrim(pr.historia_origen) <> ''
  AND pr.nombre IN (
        'Aceite de ungurahui 250 ml', 'Aceite de aguaje 250 ml', 'Camu camu en polvo 150 g',
        'Textil bordado shipibo', 'Cacao en grano fermentado 1 kg', 'Pasta de cacao 500 g',
        'Infusion de guayusa 80 g', 'Cafe tostado en grano 500 g', 'Cafe molido 250 g',
        'Canasta tejida de tamshi')
ON CONFLICT (producto_id) DO NOTHING;

-- Existencias iniciales (CCPIP-361, RNF-15): por cada producto sembrado, un lote en el
-- centro nacional, su movimiento de ENTRADA ligado a ese lote y la fila de stock. Va en un
-- solo comando para que los tres queden juntos o ninguno.
--
-- Solo los diez productos sembrados arriba: un producto creado desde la plataforma nunca
-- recibe existencias de demostracion. Y solo si el producto aun no tiene su carga inicial,
-- asi que volver a correr no duplica nada (RNF-28) ni pisa lo que el inventario ya movio.
-- Las artesanias no vencen (la linea lo decide).
WITH sembrado AS (
    SELECT pr.id AS producto_id, acopio.id AS ubicacion_id, li.requiere_vencimiento
    FROM producto pr
    JOIN productor p ON p.id = pr.productor_id
    JOIN usuario u ON u.id = p.usuario_id
    JOIN linea_producto li ON li.id = pr.linea_id
    JOIN ubicacion acopio ON acopio.nombre = 'Centro Nacional Lima' AND acopio.tipo = 'PUNTO_ACOPIO'
    WHERE u.correo IN ('productor@ccpip.test', 'productor2@ccpip.test', 'productor3@ccpip.test')
      AND pr.nombre IN (
        'Aceite de ungurahui 250 ml', 'Aceite de aguaje 250 ml', 'Camu camu en polvo 150 g',
        'Textil bordado shipibo', 'Cacao en grano fermentado 1 kg', 'Pasta de cacao 500 g',
        'Infusion de guayusa 80 g', 'Cafe tostado en grano 500 g', 'Cafe molido 250 g',
        'Canasta tejida de tamshi')
      AND NOT EXISTS (
        SELECT 1 FROM movimiento_inventario mv
        WHERE mv.producto_id = pr.id AND mv.observacion = 'Carga inicial de demostracion')
),
nuevo_lote AS (
    INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                      fecha_produccion, fecha_vencimiento, registrado_en)
    SELECT producto_id, ubicacion_id, 'L-' || lpad(producto_id::text, 4, '0'), 100, 100,
           current_date - 30,
           CASE WHEN requiere_vencimiento THEN current_date + 365 END,
           now()
    FROM sembrado
    ON CONFLICT (producto_id, codigo) DO NOTHING
    RETURNING id, producto_id, ubicacion_id, cantidad_inicial
),
entrada AS (
    INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion)
    SELECT producto_id, ubicacion_id, id, 'ENTRADA', cantidad_inicial, 'Carga inicial de demostracion'
    FROM nuevo_lote
    RETURNING producto_id, ubicacion_id, cantidad
)
INSERT INTO stock (producto_id, ubicacion_id, cantidad)
SELECT producto_id, ubicacion_id, cantidad FROM entrada
ON CONFLICT (producto_id, ubicacion_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- Cuenta bancaria y autorizaciones (CCPIP-269, RF-142): solo se compra lo autorizado a un
-- productor con cuenta ACTIVA. Los tres productores con tienda venden; Kukama
-- (productor_nuevo) aun no registra su cuenta, asi que lo que publique se ve en
-- "Venta pausada" (Figma CW-03c) hasta que la registre (CCPIP-32).
-- ---------------------------------------------------------------------------------------

INSERT INTO cuenta_bancaria (productor_id, banco, titular, numero_cuenta, cci)
SELECT p.id, 'Banco de la Nación', p.comunidad, d.numero, d.cci
FROM productor p
JOIN usuario u ON u.id = p.usuario_id
JOIN (VALUES
    ('productor@ccpip.test',  '04-110-000001', '01811000041100000117'),
    ('productor2@ccpip.test', '04-110-000002', '01811000041100000217'),
    ('productor3@ccpip.test', '04-110-000003', '01811000041100000317')
) AS d(correo, numero, cci) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM cuenta_bancaria c WHERE c.productor_id = p.id);

INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
SELECT pr.productor_id, pr.id, 'APROBADA', now()
FROM producto pr
JOIN productor p ON p.id = pr.productor_id
JOIN usuario u ON u.id = p.usuario_id
WHERE u.correo IN ('productor@ccpip.test', 'productor2@ccpip.test', 'productor3@ccpip.test')
  -- Solo los sembrados: lo que un productor publique desde la plataforma pasa por la
  -- revision de la camara, aunque esta semilla vuelva a correr.
  AND pr.nombre IN (
        'Aceite de ungurahui 250 ml', 'Aceite de aguaje 250 ml', 'Camu camu en polvo 150 g',
        'Textil bordado shipibo', 'Cacao en grano fermentado 1 kg', 'Pasta de cacao 500 g',
        'Infusion de guayusa 80 g', 'Cafe tostado en grano 500 g', 'Cafe molido 250 g',
        'Canasta tejida de tamshi')
ON CONFLICT (productor_id, producto_id) DO NOTHING;

-- =======================================================================================
-- Datos del Sprint 0 completos: lo que hace falta para recorrer el primer flujo de compra
-- nacional con recojo en un ambiente recien creado (CCPIP-148, RNF-28).
--
-- Todo es idempotente y nunca pisa lo que ya movio la plataforma: cada bloque solo siembra
-- lo que aun no existe, y los pedidos, el carrito y las existencias se siembran una sola vez.
-- Se agrega sin tocar los diez productos ni las tres tiendas de arriba: lo nuevo son casos
-- limite en dos tiendas nuevas y un conjunto de pedidos para recorrer las pantallas del punto.
-- =======================================================================================

-- ---------------------------------------------------------------------------------------
-- 1. Cuentas adicionales (misma clave publica de demostracion que las de arriba: Demo1234!)
-- ---------------------------------------------------------------------------------------
INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado) VALUES
    -- Segundo comprador: con su pedido en curso y un carrito armado, sin tocar al comprador de arriba
    ('consumidor2@ccpip.test',          '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Lucía Ramírez',                       1, true),
    -- Un administrador por cada punto de acopio que aun no tenia (RNF-05: cada uno ve solo el suyo)
    ('admin_acopio_loreto@ccpip.test',  '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio Loreto',      3, true),
    ('admin_acopio_cusco@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio Cusco',       3, true),
    ('admin_acopio_ucayali@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio Ucayali',     3, true),
    ('admin_acopio_san_martin@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio San Martín', 3, true),
    ('admin_acopio_junin@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio Junín',      3, true),
    -- Dos productores mas, para los casos limite de compra
    ('productor4@ccpip.test',           '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Ese Eja',                   2, true),
    ('productor5@ccpip.test',           '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Yanesha',                   2, true)
ON CONFLICT (correo) DO NOTHING;

INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
SELECT u.id, l.id
FROM (VALUES
    ('admin_acopio_loreto@ccpip.test',     'Centro Regional Loreto'),
    ('admin_acopio_cusco@ccpip.test',      'Centro Regional Cusco'),
    ('admin_acopio_ucayali@ccpip.test',    'Centro Regional Ucayali'),
    ('admin_acopio_san_martin@ccpip.test', 'Centro Regional San Martin'),
    ('admin_acopio_junin@ccpip.test',      'Centro Regional Junin')
) AS d(correo, punto)
JOIN usuario u ON u.correo = d.correo
JOIN ubicacion l ON l.nombre = d.punto AND l.tipo = 'PUNTO_ACOPIO'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- 2. Dos tiendas nuevas, publicadas y con origen verificado
--    Ese Eja vende con normalidad; Yanesha tiene la cuenta bancaria desactivada (mas abajo).
-- ---------------------------------------------------------------------------------------
INSERT INTO productor (usuario_id, comunidad, region, historia, origen_verificado, verificado_en,
                       nombre_tienda, descripcion_corta, cita, pueblo, localidad, socios,
                       tienda_publicada, tienda_publicada_en)
SELECT u.id, d.comunidad, d.region, d.historia, true, current_date - 90,
       d.tienda, d.descripcion, d.cita, d.pueblo, d.localidad, d.socios, true, now()
FROM usuario u
JOIN (VALUES
    ('productor4@ccpip.test', 'Comunidad Ese Eja de Infierno', 'Madre de Dios',
     'Recogemos la castana del suelo del bosque cuando cae sola, sin tumbar un solo arbol. Cada familia cuida sus propios castanales desde hace generaciones.',
     'Asoc. Ese Eja', 'Castanas amazonicas y sus derivados, recogidas por familias ese eja de Infierno.',
     'El castanal nos cuida y nosotros lo cuidamos.', 'Ese Eja', 'Infierno', 22),
    ('productor5@ccpip.test', 'Comunidad Yanesha de Oxapampa', 'Pasco',
     'Cosechamos hierbas de altura en las laderas de Oxapampa y las secamos a la sombra para conservar su aroma.',
     'Asoc. Yanesha', 'Infusiones y hierbas de altura de la selva central.',
     'Las hierbas de la ladera curan lo que el monte nos da.', 'Yanesha', 'Oxapampa', 15)
) AS d(correo, comunidad, region, historia, tienda, descripcion, cita, pueblo, localidad, socios) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

INSERT INTO ubicacion (tipo, nombre, productor_id, departamento, distrito, direccion)
SELECT 'ALMACEN_PRODUCTOR', 'Almacén ' || p.comunidad, p.id, p.region, p.localidad, 'Almacén de la comunidad'
FROM productor p JOIN usuario u ON u.id = p.usuario_id
WHERE u.correo IN ('productor4@ccpip.test', 'productor5@ccpip.test')
  AND NOT EXISTS (SELECT 1 FROM ubicacion x WHERE x.productor_id = p.id AND x.tipo = 'ALMACEN_PRODUCTOR');

-- ---------------------------------------------------------------------------------------
-- 3. Productos de los casos limite (todos PUBLICADOS y con su historia aprobada)
--    Castanas: dos lotes, para ver que sale primero el que vence antes (RNF-14).
--    Aceite de castana: un lote por vencer.
--    Harina de castana: queda agotada con las ventas de abajo.
--    Infusion de muna: de la tienda con la venta pausada.
-- ---------------------------------------------------------------------------------------
INSERT INTO producto (productor_id, nombre, descripcion, historia_origen, linea_id, unidad_medida_id, precio, es_materia_prima, estado)
SELECT p.id, d.nombre, d.descripcion, d.historia, l.id, m.id, d.precio, false, 'PUBLICADO'
FROM (VALUES
    ('Comunidad Ese Eja de Infierno', 'Castanas amazonicas 250 g', 'Almendras de castana peladas a mano.',
     'Se recogen del suelo del bosque cuando caen del arbol, nunca se tumba el arbol.', 'SUPERFRUTOS', 'G', 38.00),
    ('Comunidad Ese Eja de Infierno', 'Aceite de castana 100 ml', 'Aceite prensado de la almendra de castana.',
     'Se prensa en la comunidad el mismo mes de la cosecha.', 'ACEITES', 'ML', 44.00),
    ('Comunidad Ese Eja de Infierno', 'Harina de castana 300 g', 'Almendra de castana molida fina, para panes y postres.',
     'La molienda se hace en piedra, como se hizo siempre en la comunidad.', 'SUPERFRUTOS', 'G', 29.00),
    ('Comunidad Yanesha de Oxapampa', 'Infusion de muna 60 g', 'Hoja de muna secada a la sombra.',
     'Se cosecha en las laderas de Oxapampa antes de la floracion.', 'INFUSIONES', 'G', 21.00)
) AS d(comunidad, nombre, descripcion, historia, linea, unidad, precio)
JOIN productor p ON p.comunidad = d.comunidad
JOIN linea_producto l ON l.codigo = d.linea
JOIN unidad_medida m ON m.codigo = d.unidad
WHERE NOT EXISTS (SELECT 1 FROM producto x WHERE x.productor_id = p.id AND x.nombre = d.nombre);

UPDATE producto SET publicado_en = creado_en
WHERE estado = 'PUBLICADO' AND publicado_en IS NULL
  AND nombre IN ('Castanas amazonicas 250 g', 'Aceite de castana 100 ml', 'Harina de castana 300 g', 'Infusion de muna 60 g')
  AND productor_id IN (SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                       WHERE u.correo IN ('productor4@ccpip.test', 'productor5@ccpip.test'));

INSERT INTO producto_linea (producto_id, linea_id)
SELECT pr.id, pr.linea_id FROM producto pr
WHERE pr.linea_id IS NOT NULL
  AND pr.nombre IN ('Castanas amazonicas 250 g', 'Aceite de castana 100 ml', 'Harina de castana 300 g', 'Infusion de muna 60 g')
  AND pr.productor_id IN (SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                          WHERE u.correo IN ('productor4@ccpip.test', 'productor5@ccpip.test'))
ON CONFLICT DO NOTHING;

INSERT INTO producto_historia (producto_id, texto_final, aprobada_en)
SELECT pr.id, pr.historia_origen, now() FROM producto pr
WHERE pr.historia_origen IS NOT NULL AND btrim(pr.historia_origen) <> ''
  AND pr.nombre IN ('Castanas amazonicas 250 g', 'Aceite de castana 100 ml', 'Harina de castana 300 g', 'Infusion de muna 60 g')
  AND pr.productor_id IN (SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                          WHERE u.correo IN ('productor4@ccpip.test', 'productor5@ccpip.test'))
ON CONFLICT (producto_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- 4. Cuenta bancaria y autorizaciones (RF-142): Ese Eja vende; Yanesha tiene la cuenta
--    DESACTIVADA, asi que su oferta se ve pero queda en "Venta pausada" (Figma CW-03c).
-- ---------------------------------------------------------------------------------------
INSERT INTO cuenta_bancaria (productor_id, banco, titular, numero_cuenta, cci, estado)
SELECT p.id, 'Banco de la Nación', p.comunidad, d.numero, d.cci, d.estado
FROM productor p
JOIN usuario u ON u.id = p.usuario_id
JOIN (VALUES
    ('productor4@ccpip.test', '04-110-000004', '01811000041100000417', 'ACTIVA'),
    ('productor5@ccpip.test', '04-110-000005', '01811000041100000517', 'DESACTIVADA')
) AS d(correo, numero, cci, estado) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM cuenta_bancaria c WHERE c.productor_id = p.id);

INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
SELECT pr.productor_id, pr.id, 'APROBADA', now()
FROM producto pr
-- Solo los sembrados: lo que estos productores publiquen desde la plataforma pasa por la revision de la camara
WHERE pr.nombre IN ('Castanas amazonicas 250 g', 'Aceite de castana 100 ml', 'Harina de castana 300 g', 'Infusion de muna 60 g')
  AND pr.productor_id IN (SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                          WHERE u.correo IN ('productor4@ccpip.test', 'productor5@ccpip.test'))
ON CONFLICT (productor_id, producto_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- 5. Existencias de los productos nuevos en el centro nacional (CCPIP-361, RNF-15): cada
--    lote con su movimiento de ENTRADA y la fila de stock, en un solo comando. Un lote ya
--    sembrado no se vuelve a crear, asi que volver a correr no duplica ni pisa lo vendido.
-- ---------------------------------------------------------------------------------------
WITH definicion(producto, sufijo, cantidad, vence_en, produccion_hace) AS (VALUES
    ('Castanas amazonicas 250 g', 'A', 40,  25,  60),   -- vence antes: sale primero
    ('Castanas amazonicas 250 g', 'B', 160, 330, 20),
    ('Aceite de castana 100 ml',  '',  60,  12,  80),   -- por vencer
    ('Harina de castana 300 g',   '',  25,  200, 30),   -- se agota con los pedidos
    ('Infusion de muna 60 g',     '',  50,  300, 15)
),
sembrado AS (
    SELECT pr.id AS producto_id, acopio.id AS ubicacion_id,
           'L-' || lpad(pr.id::text, 4, '0') || d.sufijo AS codigo, d.cantidad, d.vence_en, d.produccion_hace
    FROM definicion d
    JOIN producto pr ON pr.nombre = d.producto
    JOIN productor p ON p.id = pr.productor_id
    JOIN usuario u ON u.id = p.usuario_id
    JOIN ubicacion acopio ON acopio.nombre = 'Centro Nacional Lima' AND acopio.tipo = 'PUNTO_ACOPIO'
    WHERE u.correo IN ('productor4@ccpip.test', 'productor5@ccpip.test')
),
nuevo_lote AS (
    INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                      fecha_produccion, fecha_vencimiento, registrado_en)
    SELECT producto_id, ubicacion_id, codigo, cantidad, cantidad,
           current_date - produccion_hace, current_date + vence_en, now() - interval '3 days'
    FROM sembrado
    ON CONFLICT (producto_id, codigo) DO NOTHING
    RETURNING id, producto_id, ubicacion_id, cantidad_inicial
),
entrada AS (
    INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion, registrado_en)
    SELECT producto_id, ubicacion_id, id, 'ENTRADA', cantidad_inicial, 'Carga inicial de demostracion', now() - interval '3 days'
    FROM nuevo_lote
    RETURNING producto_id, ubicacion_id, cantidad
)
INSERT INTO stock (producto_id, ubicacion_id, cantidad)
SELECT producto_id, ubicacion_id, sum(cantidad) FROM entrada GROUP BY producto_id, ubicacion_id
ON CONFLICT (producto_id, ubicacion_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- 6. Pedidos de demostracion en todos los estados del Sprint 0 (para recoger en el Centro
--    Nacional Lima). Siguen el flujo real de CCPIP-62, 157, 158, 159 y 71: el pedido nace en
--    PENDIENTE_PAGO, un pago APROBADO lo confirma, recien ahi se descuenta el stock (lotes
--    que vencen antes primero), y el punto lo deja listo y lo entrega.
--
--    La funcion es temporal (vive solo en esta sesion). Un pedido que ya existe se salta, y
--    uno al que le falte producto o existencias tambien: un dato de demostracion nunca debe
--    impedir que la aplicacion arranque. Por eso lote y stock se descuentan una sola vez.
-- ---------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.sembrar_pedido(
    p_numero text, p_comprador text, p_estado text, p_hace interval, p_lineas jsonb,
    p_marca text DEFAULT 'VISA', p_celular text DEFAULT NULL, p_persona text DEFAULT NULL, p_recoge text DEFAULT NULL
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    v_usuario bigint;
    v_punto bigint;
    v_admin bigint;
    v_pedido bigint;
    v_total numeric(12,2) := 0;
    v_creado timestamptz := now() - p_hace;
    v_confirmado timestamptz := now() - p_hace + p_hace * 0.05;
    v_listo timestamptz := now() - p_hace + p_hace * 0.5;
    v_entregado timestamptz := now() - p_hace + p_hace * 0.85;
    v_cobrado boolean := p_estado IN ('CONFIRMADO', 'LISTO_PARA_RECOJO', 'ENTREGADO');
    v_linea jsonb;
    v_producto record;
    v_item record;
    v_lote record;
    v_pendiente numeric;
    v_sale numeric;
BEGIN
    IF EXISTS (SELECT 1 FROM pedido WHERE numero = p_numero) THEN
        RETURN;
    END IF;
    SELECT id INTO v_usuario FROM usuario WHERE correo = p_comprador;
    SELECT id INTO v_punto FROM ubicacion WHERE tipo = 'PUNTO_ACOPIO' AND nombre = 'Centro Nacional Lima';
    SELECT id INTO v_admin FROM usuario WHERE correo = 'admin_acopio@ccpip.test';
    IF v_usuario IS NULL OR v_punto IS NULL OR v_admin IS NULL THEN
        RAISE NOTICE 'Pedido % no sembrado: falta el comprador, el punto o su administrador.', p_numero;
        RETURN;
    END IF;

    -- Total y existencias: si algo no alcanza, este pedido no se siembra (y no se toca nada)
    FOR v_linea IN SELECT * FROM jsonb_array_elements(p_lineas) LOOP
        SELECT pr.id, pr.precio INTO v_producto FROM producto pr WHERE pr.nombre = v_linea->>'p' ORDER BY pr.id LIMIT 1;
        IF v_producto.id IS NULL OR v_producto.precio IS NULL THEN
            RAISE NOTICE 'Pedido % no sembrado: no existe el producto %.', p_numero, v_linea->>'p';
            RETURN;
        END IF;
        IF v_cobrado AND (SELECT coalesce(sum(l.cantidad_actual), 0) FROM lote l
                          WHERE l.producto_id = v_producto.id AND l.ubicacion_id = v_punto AND l.cantidad_actual > 0
                            AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= current_date)) < (v_linea->>'c')::numeric THEN
            RAISE NOTICE 'Pedido % no sembrado: no alcanzan las existencias de %.', p_numero, v_linea->>'p';
            RETURN;
        END IF;
        v_total := v_total + v_producto.precio * (v_linea->>'c')::numeric;
    END LOOP;

    INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, estado, total, confirmado_en, actualizado_en, creado_en)
    VALUES (p_numero, v_usuario, 'RECOJO', v_punto, 'PENDIENTE_PAGO', v_total, NULL, v_creado, v_creado)
    RETURNING id INTO v_pedido;
    INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id, registrado_en)
    VALUES (v_pedido, 'PENDIENTE_PAGO', v_usuario, v_creado);

    FOR v_linea IN SELECT * FROM jsonb_array_elements(p_lineas) LOOP
        INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
        SELECT v_pedido, pr.id, pr.productor_id, (v_linea->>'c')::numeric, pr.precio, pr.precio * (v_linea->>'c')::numeric
        FROM producto pr WHERE pr.nombre = v_linea->>'p' ORDER BY pr.id LIMIT 1;
    END LOOP;

    -- Pago (CCPIP-62, CCPIP-163): los pedidos sin pagar tienen un intento rechazado; los demas, uno aprobado
    IF v_cobrado THEN
        INSERT INTO pago (pedido_id, clave_idempotencia, monto, pasarela, marca_tarjeta, creado_en)
        VALUES (v_pedido, md5('seed-pago-' || p_numero)::uuid, v_total, 'SIMULADA', p_marca, v_creado);
        UPDATE pago SET estado = 'APROBADO', resuelto_en = v_confirmado - interval '1 minute',
                        referencia_pasarela = 'SIM-' || p_numero, codigo_respuesta = '00', mensaje_respuesta = 'Aprobado'
        WHERE pedido_id = v_pedido;
    ELSE
        INSERT INTO pago (pedido_id, clave_idempotencia, monto, estado, pasarela, marca_tarjeta, codigo_respuesta,
                          mensaje_respuesta, creado_en, resuelto_en)
        VALUES (v_pedido, md5('seed-pago-' || p_numero)::uuid, v_total, 'RECHAZADO', 'SIMULADA', p_marca, '05',
                'Tarjeta rechazada por el banco emisor', v_creado, v_creado + interval '20 seconds');
    END IF;

    IF p_estado = 'CANCELADO' THEN
        UPDATE pedido SET estado = 'CANCELADO', actualizado_en = v_listo WHERE id = v_pedido;
        INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id, registrado_en) VALUES (v_pedido, 'CANCELADO', v_usuario, v_listo);
    ELSIF v_cobrado THEN
        -- Confirmar y descontar en el mismo paso: la persona autorizada y el celular se registran al confirmar (CCPIP-157)
        UPDATE pedido SET estado = 'CONFIRMADO', confirmado_en = v_confirmado, actualizado_en = v_confirmado,
                          persona_autorizada = p_persona, celular_contacto = p_celular
        WHERE id = v_pedido;
        INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id, registrado_en) VALUES (v_pedido, 'CONFIRMADO', v_usuario, v_confirmado);

        FOR v_item IN SELECT i.id, i.producto_id, i.cantidad FROM item_pedido i WHERE i.pedido_id = v_pedido ORDER BY i.id LOOP
            v_pendiente := v_item.cantidad;
            FOR v_lote IN SELECT l.id, l.cantidad_actual FROM lote l
                          WHERE l.producto_id = v_item.producto_id AND l.ubicacion_id = v_punto AND l.cantidad_actual > 0
                            AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= current_date)
                          ORDER BY l.fecha_vencimiento NULLS LAST, l.registrado_en, l.id FOR UPDATE LOOP
                EXIT WHEN v_pendiente <= 0;
                v_sale := least(v_lote.cantidad_actual, v_pendiente);
                UPDATE lote SET cantidad_actual = cantidad_actual - v_sale WHERE id = v_lote.id;
                UPDATE stock SET cantidad = cantidad - v_sale, actualizado_en = v_confirmado
                WHERE producto_id = v_item.producto_id AND ubicacion_id = v_punto;
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, pedido_id, item_pedido_id,
                                                   usuario_id, observacion, registrado_en)
                VALUES (v_item.producto_id, v_punto, v_lote.id, 'SALIDA_VENTA', v_sale, v_pedido, v_item.id,
                        v_usuario, 'Venta del pedido ' || p_numero, v_confirmado);
                v_pendiente := v_pendiente - v_sale;
            END LOOP;
        END LOOP;

        IF p_estado IN ('LISTO_PARA_RECOJO', 'ENTREGADO') THEN
            UPDATE pedido SET estado = 'LISTO_PARA_RECOJO', actualizado_en = v_listo WHERE id = v_pedido;
            INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id, registrado_en) VALUES (v_pedido, 'LISTO_PARA_RECOJO', v_admin, v_listo);
        END IF;
        IF p_estado = 'ENTREGADO' THEN
            UPDATE pedido SET estado = 'ENTREGADO', actualizado_en = v_entregado, recogido_por = p_recoge, recogido_celular = p_celular
            WHERE id = v_pedido;
            INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id, registrado_en) VALUES (v_pedido, 'ENTREGADO', v_admin, v_entregado);
        END IF;
    END IF;
END;
$$;

-- Numeros en el formato de la plataforma (CCP-<id>), en el rango 900000 para no chocar con los reales. consumidor2 es Lucia Ramirez.
SELECT pg_temp.sembrar_pedido('CCP-900131', 'consumidor2@ccpip.test', 'ENTREGADO',         interval '7 hours',
    '[{"p":"Cafe tostado en grano 500 g","c":1}]', 'VISA', '987654321', NULL, 'Lucía Ramírez');
SELECT pg_temp.sembrar_pedido('CCP-900139', 'consumidor@ccpip.test',  'CONFIRMADO',        interval '3 hours',
    '[{"p":"Cacao en grano fermentado 1 kg","c":2},{"p":"Pasta de cacao 500 g","c":1}]', 'MASTERCARD', '987654321');
SELECT pg_temp.sembrar_pedido('CCP-900144', 'consumidor2@ccpip.test', 'CONFIRMADO',        interval '90 minutes',
    '[{"p":"Aceite de castana 100 ml","c":3}]', 'VISA', '912345678');
-- Julio Paredes es la persona autorizada: el pedido de ayer ya se le entrego, y el de hoy lo espera
SELECT pg_temp.sembrar_pedido('CCP-900150', 'consumidor@ccpip.test',  'ENTREGADO',         interval '30 hours',
    '[{"p":"Harina de castana 300 g","c":10}]', 'VISA', '987654321', 'Julio Paredes', 'Julio Paredes');
SELECT pg_temp.sembrar_pedido('CCP-900152', 'consumidor2@ccpip.test', 'LISTO_PARA_RECOJO', interval '5 hours',
    '[{"p":"Castanas amazonicas 250 g","c":2}]', 'MASTERCARD', '912345678');
SELECT pg_temp.sembrar_pedido('CCP-900155', 'consumidor@ccpip.test',  'LISTO_PARA_RECOJO', interval '4 hours',
    '[{"p":"Harina de castana 300 g","c":15},{"p":"Camu camu en polvo 150 g","c":1}]', 'VISA', '987654321', 'Julio Paredes');
SELECT pg_temp.sembrar_pedido('CCP-900160', 'consumidor2@ccpip.test', 'PENDIENTE_PAGO',    interval '40 minutes',
    '[{"p":"Aceite de aguaje 250 ml","c":1},{"p":"Aceite de ungurahui 250 ml","c":1}]', 'VISA', '912345678');
SELECT pg_temp.sembrar_pedido('CCP-900161', 'consumidor@ccpip.test',  'CANCELADO',         interval '26 hours',
    '[{"p":"Textil bordado shipibo","c":1}]', 'MASTERCARD', '987654321');

-- ---------------------------------------------------------------------------------------
-- 7. Un carrito armado para el segundo comprador: una oferta comprable y una en venta
--    pausada (tienda Yanesha). Solo se arma si aun no tiene carrito: si Lucia lo vacia o lo
--    cambia, volver a correr no se lo devuelve. El carrito del primer comprador queda vacio.
-- ---------------------------------------------------------------------------------------
WITH nuevo AS (
    INSERT INTO carrito (usuario_id)
    SELECT u.id FROM usuario u
    WHERE u.correo = 'consumidor2@ccpip.test'
      AND NOT EXISTS (SELECT 1 FROM carrito c WHERE c.usuario_id = u.id)
    RETURNING id
)
INSERT INTO item_carrito (carrito_id, producto_id, cantidad)
SELECT nuevo.id, pr.id, d.cantidad
FROM nuevo
JOIN (VALUES ('Aceite de ungurahui 250 ml', 2), ('Infusion de muna 60 g', 1)) AS d(nombre, cantidad) ON true
JOIN LATERAL (SELECT x.id FROM producto x WHERE x.nombre = d.nombre ORDER BY x.id LIMIT 1) pr ON true
ON CONFLICT (carrito_id, producto_id) DO NOTHING;
