#!/bin/bash
set -euo pipefail

: "${MYSQL_HOST:=mysql}"
: "${MYSQL_ROOT_PASSWORD:=reservepay}"
: "${MYSQL_DATABASE:=reservepay}"
: "${MYSQL_USER:=reservepay}"
: "${MYSQL_PASSWORD:=reservepay}"

echo "Waiting for MySQL at ${MYSQL_HOST}..."
until mysqladmin ping -h"${MYSQL_HOST}" -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent; do
  sleep 1
done

echo "Dropping and recreating database: ${MYSQL_DATABASE}"
mysql -h"${MYSQL_HOST}" -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOF
DROP DATABASE IF EXISTS \`${MYSQL_DATABASE}\`;
CREATE DATABASE \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
DROP USER IF EXISTS '${MYSQL_USER}'@'%';
CREATE USER '${MYSQL_USER}'@'%' IDENTIFIED WITH mysql_native_password BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF

echo "Applying schema.sql"
mysql -h"${MYSQL_HOST}" -uroot -p"${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" < /schema/schema.sql

echo "Applying patch-missing-tables.sql"
mysql -h"${MYSQL_HOST}" -uroot -p"${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" < /schema/patch-missing-tables.sql

echo "Database reset complete."
