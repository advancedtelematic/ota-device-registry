CREATE TABLE Ecu (
  device_uuid CHAR(36)      NOT NULL COLLATE utf8_bin,
  ecu_id      VARCHAR(64)   NOT NULL,
  ecu_type    VARCHAR(200)  NULL,
  `primary`   BOOL          NOT NULL,
  public_key  VARCHAR(4096) NOT NULL,

  PRIMARY KEY (device_uuid, ecu_id),
  CONSTRAINT `fk_ecu_device` FOREIGN KEY (device_uuid) REFERENCES Device(uuid)
);