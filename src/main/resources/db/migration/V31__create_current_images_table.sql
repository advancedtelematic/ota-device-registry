CREATE TABLE CurrentImage (
  device_uuid CHAR(36)     NOT NULL COLLATE utf8_bin,
  ecu_id      VARCHAR(64)  NOT NULL,
  filepath    VARCHAR(255) NOT NULL,
  checksum    CHAR(64)     NOT NULL,
  `size`      BIGINT       NOT NULL,

  PRIMARY KEY(device_uuid, ecu_id),
  CONSTRAINT fk_ecu FOREIGN KEY(device_uuid, ecu_id) REFERENCES Ecu(device_uuid, ecu_id)
);