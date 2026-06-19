up:
	docker compose up -d mysql redis
	docker compose run --rm db-init
	docker compose up -d --build app

up-distributed:
	docker compose up -d mysql redis
	docker compose run --rm db-init
	docker compose --profile distributed up -d --build app1 app2 nginx

down:
	docker compose --profile distributed down
	docker compose down

logs:
	docker compose logs -f app

logs-distributed:
	docker compose --profile distributed logs -f nginx app1 app2

restart:
	docker compose up -d --build --force-recreate app

restart-distributed:
	docker compose --profile distributed up -d --build --force-recreate app1 app2 nginx

reset-db:
	docker compose up -d mysql
	docker compose run --rm db-init

# 기존 볼륨 유지한 채 누락 테이블만 추가 (booking_dead_letter 등)
patch-db:
	docker compose up -d mysql
	@echo "Waiting for MySQL..."
	@docker compose exec mysql bash -c 'until mysqladmin ping -h127.0.0.1 -uroot -preservepay --silent; do sleep 1; done'
	docker compose exec -T mysql mysql -uroot -preservepay reservepay < docker/mysql/patch-missing-tables.sql
	@echo "Patch applied."
