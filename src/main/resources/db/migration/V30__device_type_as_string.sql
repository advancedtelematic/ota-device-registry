ALTER TABLE Device
MODIFY COLUMN device_type varchar(200) NOT NULL;

UPDATE Device
SET device_type = CASE WHEN device_type = "1" THEN "Vehicle" ELSE "Other" END;