CREATE TABLE DeviceInstallationResult (
    correlation_id varchar(256) NOT NULL,
    result_code VARCHAR(256) NOT NULL,
    device_uuid char(36) COLLATE utf8_bin NOT NULL,
    installation_report JSON,

    PRIMARY KEY (correlation_id, device_uuid)
);

CREATE TABLE EcuInstallationResult (
    correlation_id varchar(256) NOT NULL,
    result_code VARCHAR(256) NOT NULL,
    device_uuid char(36) COLLATE utf8_bin NOT NULL,
    ecu_id VARCHAR(64)  NOT NULL,

    PRIMARY KEY (correlation_id, device_uuid, ecu_id),
    CONSTRAINT fk_ecu_report_device_report FOREIGN KEY (correlation_id, device_uuid) REFERENCES DeviceInstallationResult(correlation_id, device_uuid)
);