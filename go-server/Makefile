postgres:
	docker run --name postgres12 -p 5432:5432 -e POSTGRES_USER=root -e POSTGRES_PASSWORD=1234 -d postgres

createdb:
	docker exec -it postgres12 createdb --username=root --owner=root gateway

dropdb:
	docker exec -it postgres12 dropdb gateway

sqlc:
	sqlc generate

server:
	go run cmd/main.go

.PHONY: postgres createdb dropdb sqlc server