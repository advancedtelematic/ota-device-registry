#!/bin/bash

set -u

mkdir entrypoint.d/ || true

echo "
CREATE DATABASE device_registry;
CREATE DATABASE device_registry_test;
GRANT ALL PRIVILEGES ON \`device\_registry%\`.* TO 'sota_test'@'%';
FLUSH PRIVILEGES;
" > entrypoint.d/db_user.sql

docker rm --force mariadb-device-registry || true
docker run -d \
  --name mariadb-device-registry \
  -p 3306:3306 \
  -v $(pwd)/entrypoint.d:/docker-entrypoint-initdb.d \
  -e MYSQL_ROOT_PASSWORD=sota-test \
  -e MYSQL_USER=sota_test \
  -e MYSQL_PASSWORD=s0ta \
  mariadb:10.2 \
  --character-set-server=utf8 --collation-server=utf8_unicode_ci \
  --max_connections=1000

MYSQL_PORT=${MYSQL_PORT-3306}

function mysqladmin_alive {
    docker run \
           --rm \
           --link mariadb-device-registry \
           mariadb:10.2 \
           mysqladmin ping --protocol=TCP -h mariadb-device-registry -P 3306 -u sota_test -ps0ta
}

TRIES=60
TIMEOUT=1s

for t in `seq $TRIES`; do
    res=$(mysqladmin_alive || true)
    if [[ $res =~ "mysqld is alive" ]]; then
        echo "mysql is ready"
        exit 0
    else
        echo "Waiting for mariadb"
        sleep $TIMEOUT
    fi
done

exit -1

