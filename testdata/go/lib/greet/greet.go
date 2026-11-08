// Package greet makes greeting messages.
package greet

import "fmt"

// Hello returns a greeting for the name.
func Hello(name string) string {
	if name == "" {
		name = "world"
	}
	return fmt.Sprintf("Hello, %s!", name)
}
