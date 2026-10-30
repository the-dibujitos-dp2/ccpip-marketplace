-- CCPIP-296: permisos configurables iniciales de cada rol. Va despues de R__01 (roles). Idempotente
-- (RNF-28): ON CONFLICT DO NOTHING no pisa lo que el superadministrador haya concedido o revocado.

-- Acciones de gobierno del subadministrador, concedidas como hasta hoy
INSERT INTO permiso_configurable (rol_id, codigo, concedido)
SELECT r.id, p.codigo, true
FROM rol r
CROSS JOIN (VALUES ('DECIDIR_AUTORIZACIONES'), ('MODERAR_PUBLICACIONES'), ('GESTIONAR_PRODUCTORES'),
                   ('GESTIONAR_DENUNCIAS'), ('SUSPENDER_CUENTAS')) AS p (codigo)
WHERE r.codigo = 'SUB_ADMIN'
ON CONFLICT (rol_id, codigo) DO NOTHING;

-- Restricciones de gobernanza para la administracion de la Camara: nunca se conceden
INSERT INTO permiso_configurable (rol_id, codigo, restringido, concedido)
SELECT r.id, p.codigo, true, false
FROM rol r
CROSS JOIN (VALUES ('EDITAR_PRODUCTOS'), ('EDITAR_TIENDAS'), ('EDITAR_PEDIDOS'),
                   ('EDITAR_DATOS_COMPRADORES'), ('EDITAR_EXISTENCIAS')) AS p (codigo)
WHERE r.codigo IN ('SUB_ADMIN', 'SUPER_ADMIN')
ON CONFLICT (rol_id, codigo) DO NOTHING;
