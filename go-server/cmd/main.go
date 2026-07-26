package main

import (
	_ "context"
	"go-proxy-gateway/api"
	"go-proxy-gateway/util"
	"log"

	_ "github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	config, err := util.LoadConfig(".")
	if err != nil {
		log.Fatal("cannot load config : ", err)
	}

	/*
		conn, err := pgxpool.New(context.Background(), config.DBSource)
		if err != nil {
			log.Fatal("cannot connect to DB : ", err)
		}
		defer conn.Close()
	*/

	server, err := api.NewServer(config)
	if err != nil {
		log.Fatal("cannot create server : ", err)
	}

	if err := server.Start(config.ServerAddress); err != nil {
		log.Fatal("cannot start server", err)
	}
}
