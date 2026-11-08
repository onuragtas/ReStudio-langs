package dev.restudio.dotnet.ide

import dev.restudio.core.ide.FileExtensionAdvertiserSuppressor

/** Roslyn LS handles the C# files and the plugin reads the project files, so the IDE must not offer another .NET IDE. */
class DotnetAdvertiserSuppressor : FileExtensionAdvertiserSuppressor(setOf("cs", "csproj", "sln", "slnx"))
