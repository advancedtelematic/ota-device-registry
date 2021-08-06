ALTER TABLE DeviceInstallationResult ADD INDEX (device_uuid);
ALTER TABLE GroupMembers ADD INDEX (device_uuid);
