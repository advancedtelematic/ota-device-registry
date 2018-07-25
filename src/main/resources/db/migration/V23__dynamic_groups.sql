ALTER TABLE DeviceGroup
ADD COLUMN `type` ENUM('dynamic', 'static') NOT NULL ,
ADD COLUMN expression VARCHAR(255) NOT NULL DEFAULT "";

