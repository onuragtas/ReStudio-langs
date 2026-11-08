package main

import (
	"net/http/httptest"
	"testing"
)

func TestHandler(t *testing.T) {
	rec := httptest.NewRecorder()
	handler(rec, httptest.NewRequest("GET", "/?name=Go", nil))
	if got := rec.Body.String(); got != "Hello, Go!" {
		t.Errorf("body = %q", got)
	}
}
