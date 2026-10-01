   CREATE TABLE room_type (
       id             BIGSERIAL     PRIMARY KEY,
       code           VARCHAR(20)   NOT NULL UNIQUE,
       name           VARCHAR(100)  NOT NULL,
       max_occupancy  INT           NOT NULL,
       base_rate      NUMERIC(10,2) NOT NULL,
       description    VARCHAR(500),
       created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version        BIGINT        NOT NULL DEFAULT 0
   );

   CREATE TABLE room (
       id            BIGSERIAL    PRIMARY KEY,
       number        VARCHAR(10)  NOT NULL UNIQUE,
       floor         INT          NOT NULL,
       view_type     VARCHAR(30),
       status        VARCHAR(20)  NOT NULL,
       room_type_id  BIGINT       NOT NULL REFERENCES room_type (id),
       created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version       BIGINT       NOT NULL DEFAULT 0
   );

   CREATE INDEX idx_room_room_type ON room (room_type_id);
   CREATE INDEX idx_room_status ON room (status);