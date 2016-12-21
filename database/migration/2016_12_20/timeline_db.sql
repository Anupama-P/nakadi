CREATE TABLE zn_data.storage (
  st_id            VARCHAR(36) NOT NULL PRIMARY KEY,
  st_type          VARCHAR(32) NOT NULL,
  st_configuration JSONB       NOT NULL
);


CREATE TABLE zn_data.timeline (
  t_id                    SERIAL                      NOT NULL PRIMARY KEY,
  et_name                 VARCHAR(255)                NOT NULL REFERENCES zn_data.event_type (et_name),
  t_order                 INT                         NOT NULL,
  st_id                   VARCHAR(36)                 NOT NULL REFERENCES zn_data.storage (st_id),
  t_storage_configuration JSONB                       NOT NULL,
  t_created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
  t_cleanup_at            TIMESTAMP WITHOUT TIME ZONE DEFAULT NULL,
  t_switched_at           TIMESTAMP WITHOUT TIME ZONE DEFAULT NULL,
  t_freed_at              TIMESTAMP WITHOUT TIME ZONE DEFAULT NULL,
  t_latest_position       TEXT                        DEFAULT NULL
);