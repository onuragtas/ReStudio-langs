package dev.restudio.php.ide

import dev.restudio.core.ide.FileExtensionAdvertiserSuppressor

/** Phpactor handles the PHP files, so the IDE must not offer another PHP IDE. */
class PhpAdvertiserSuppressor : FileExtensionAdvertiserSuppressor(setOf("php"))
