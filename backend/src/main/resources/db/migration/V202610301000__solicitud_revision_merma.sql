CREATE TABLE solicitud_revision_merma (
    id bigserial PRIMARY KEY,
    merma_movimiento_id bigint NOT NULL REFERENCES merma(movimiento_id),
    solicitante_id bigint NOT NULL REFERENCES usuario(id),
    registrada_en timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX solicitud_revision_merma_registrada_idx ON solicitud_revision_merma (registrada_en DESC);
