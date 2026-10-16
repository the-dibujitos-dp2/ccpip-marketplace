-- CCPIP-32 (RF-07), CCPIP-274 (RF-169), CCPIP-275 (RF-170) y CCPIP-276 (RF-171): el productor
-- registra, actualiza, desactiva temporalmente y reactiva su cuenta bancaria (Figma PW-19, PW-20).
-- Sin fecha de vencimiento, CVV ni datos de tarjeta: una cuenta bancaria no vence (CCPIP-129).

ALTER TABLE cuenta_bancaria
    ADD COLUMN documento_titular    varchar(11),
    ADD COLUMN tipo_cuenta          varchar(10)  NOT NULL DEFAULT 'AHORROS',
    ADD COLUMN moneda               char(3)      NOT NULL DEFAULT 'PEN',
    ADD COLUMN correo_avisos        varchar(254),
    ADD COLUMN yape_celular         varchar(9),
    ADD COLUMN motivo_desactivacion varchar(20),
    ADD COLUMN desactivada_en       timestamptz,
    ADD CONSTRAINT cuenta_bancaria_documento_valido CHECK (documento_titular ~ '^([0-9]{8}|[0-9]{11})$'),
    ADD CONSTRAINT cuenta_bancaria_tipo_valido CHECK (tipo_cuenta IN ('AHORROS', 'CORRIENTE')),
    ADD CONSTRAINT cuenta_bancaria_moneda_valida CHECK (moneda IN ('PEN', 'USD')),
    ADD CONSTRAINT cuenta_bancaria_cci_valido CHECK (cci IS NULL OR cci ~ '^[0-9]{20}$'),
    ADD CONSTRAINT cuenta_bancaria_yape_valido CHECK (yape_celular IS NULL OR yape_celular ~ '^9[0-9]{8}$'),
    ADD CONSTRAINT cuenta_bancaria_motivo_valido
        CHECK (motivo_desactivacion IS NULL OR motivo_desactivacion IN ('ROBO', 'BLOQUEO_BANCO', 'SEGURIDAD', 'OTRO')),
    -- Una cuenta desactivada dice por que y desde cuando; una activa no arrastra el motivo anterior
    ADD CONSTRAINT cuenta_bancaria_desactivacion_coherente CHECK (
        (estado = 'ACTIVA' AND motivo_desactivacion IS NULL AND desactivada_en IS NULL)
     OR (estado = 'DESACTIVADA' AND motivo_desactivacion IS NOT NULL AND desactivada_en IS NOT NULL));

-- La desactivacion pide un motivo; las cuentas sembradas antes de este cambio estan activas.
COMMENT ON COLUMN cuenta_bancaria.documento_titular IS 'DNI (8) o RUC (11) del titular. Nulo solo en cuentas anteriores a CCPIP-32.';
COMMENT ON COLUMN cuenta_bancaria.tipo_cuenta IS 'AHORROS o CORRIENTE.';
COMMENT ON COLUMN cuenta_bancaria.moneda IS 'PEN o USD. Las ventas se cobran en soles (RNF-43); la moneda es la de la cuenta.';
COMMENT ON COLUMN cuenta_bancaria.correo_avisos IS 'Correo donde el productor quiere los avisos de pago. Opcional.';
COMMENT ON COLUMN cuenta_bancaria.yape_celular IS 'Metodo complementario opcional: Yape no reemplaza a la cuenta bancaria (RF-07).';
COMMENT ON COLUMN cuenta_bancaria.motivo_desactivacion IS 'CCPIP-275: ROBO, BLOQUEO_BANCO, SEGURIDAD u OTRO. Solo mientras esta DESACTIVADA.';
COMMENT ON COLUMN cuenta_bancaria.desactivada_en IS 'CCPIP-275: desde cuando esta desactivada. Las ventas nuevas se pausan; los pedidos ya pagados siguen su curso.';
