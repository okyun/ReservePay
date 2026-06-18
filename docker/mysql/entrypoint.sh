#!/bin/bash
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD:=reservepay}"
: "${MYSQL_DATABASE:=reservepay}"
: "${MYSQL_USER:=reservepay}"
: "${MYSQL_PASSWORD:=reservepay}"

echo "Starting MySQL..."
docker-entrypoint.sh mysqld &
mysqld_pid=$!

cleanup() {
  kill "${mysqld_pid}" 2>/dev/null || true
  wait "${mysqld_pid}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Waiting for MySQL to be ready..."
until mysqladmin ping -h127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent 2>/dev/null; do
  sleep 1
done

echo "Ensuring application user: ${MYSQL_USER}"
mysql -h127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOF
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
DROP USER IF EXISTS '${MYSQL_USER}'@'%';
CREATE USER '${MYSQL_USER}'@'%' IDENTIFIED WITH mysql_native_password BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF

echo "MySQL user ensured."
wait "${mysqld_pid}"
