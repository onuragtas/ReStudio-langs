package dev.restudio.go.ide

import dev.restudio.core.ide.FileExtensionAdvertiserSuppressor

/** gopls handles the Go files, so the IDE must not offer another Go plugin. */
class GoAdvertiserSuppressor : FileExtensionAdvertiserSuppressor(setOf("go"))
