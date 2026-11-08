// Command server serves greetings over HTTP.
package main

import (
	"log"
	"net/http"

	"example.com/shop/lib/greet"
)

func handler(w http.ResponseWriter, r *http.Request) {
	_, _ = w.Write([]byte(greet.Hello(r.URL.Query().Get("name"))))
}

func main() {
	http.HandleFunc("/", handler)
	log.Println("listening on :8080")
	log.Fatal(http.ListenAndServe(":8080", nil))
}
