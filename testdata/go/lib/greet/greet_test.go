package greet

import "testing"

func TestHello(t *testing.T) {
	cases := map[string]string{"": "Hello, world!", "Go": "Hello, Go!"}
	for in, want := range cases {
		if got := Hello(in); got != want {
			t.Errorf("Hello(%q) = %q, want %q", in, got, want)
		}
	}
}
