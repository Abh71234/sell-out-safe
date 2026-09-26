# Sell-out safe — Reservation API (Java 21)
#
#   make up        build & start Postgres + authority + reservation-api
#   make check     run the organiser Go checks (TRACK=backend) in a container
#   make logs      tail the API logs
#   make authority MODE=down|slow|healthy|conflicting   toggle the mock authority
#   make reset     reset authority + API state
#   make down      stop everything and wipe volumes

COMPOSE ?= docker compose
MODE    ?= healthy

.PHONY: up down build check logs authority reset ps restart-api

up:
	$(COMPOSE) up -d --build postgres authority reservation-api
	@echo "authority       http://127.0.0.1:9001"
	@echo "reservation-api http://127.0.0.1:8080"

build:
	$(COMPOSE) build

check:
	$(COMPOSE) run --rm checks

logs:
	$(COMPOSE) logs -f --tail=100 reservation-api

ps:
	$(COMPOSE) ps

authority:
	curl -sS -X POST http://127.0.0.1:9001/admin/mode \
		-H 'Content-Type: application/json' -d '{"mode":"$(MODE)"}'
	@echo

reset:
	curl -sS -X POST http://127.0.0.1:9001/admin/reset -H 'Content-Type: application/json' -d '{}'
	curl -sS -X POST http://127.0.0.1:8080/admin/reset -H 'Content-Type: application/json' -d '{}'
	@echo

restart-api:
	$(COMPOSE) restart reservation-api

down:
	$(COMPOSE) down -v
