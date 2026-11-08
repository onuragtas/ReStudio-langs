package dev.restudio.web.ide

import dev.restudio.core.ide.FileExtensionAdvertiserSuppressor

/** vtsls and the other web servers handle these files, so the IDE must not offer another web IDE. */
class WebAdvertiserSuppressor : FileExtensionAdvertiserSuppressor(
  setOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "vue", "css", "scss", "less"),
)
