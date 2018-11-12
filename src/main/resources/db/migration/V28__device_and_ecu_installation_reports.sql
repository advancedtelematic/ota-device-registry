CREATE TABLE DeviceReport (
    device_uuid char(36) COLLATE utf8_bin NOT NULL,
    correlation_id varchar(256) NOT NULL,
    result_code SMALLINT NOT NULL
);

CREATE TABLE EcuReport (
    ecu_serial VARCHAR(64)  NOT NULL,
    correlation_id varchar(256) NOT NULL,
    result_code SMALLINT NOT NULL
);