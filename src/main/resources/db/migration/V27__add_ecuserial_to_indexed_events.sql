ALTER TABLE IndexedEvents
ADD COLUMN ecu_serial VARCHAR(64),
ADD COLUMN result_code SMALLINT,
MODIFY COLUMN correlation_id varchar(256) NULL