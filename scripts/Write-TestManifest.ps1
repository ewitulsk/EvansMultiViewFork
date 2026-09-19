param([Parameter(Mandatory)][string]$ArtifactPath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (Get-Command rg -ErrorAction SilentlyContinue) {
        $paths = @(& rg --files src scripts gradle)
        if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate test sources' }
    } else {
        $paths = @('src', 'scripts', 'gradle') | ForEach-Object {
            Get-ChildItem -LiteralPath $_ -Recurse -File | ForEach-Object { $_.FullName.Substring($projectRoot.Length + 1) }
        }
    }
    $paths += @('build.gradle', 'settings.gradle', 'gradle.properties')
    $hashes = @($paths | Sort-Object -Unique | ForEach-Object {
        $destination = Join-Path $ArtifactPath (Join-Path 'source' $_)
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination)
        Copy-Item -LiteralPath $_ -Destination $destination
        @{ path=$_; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
    })
    $hashes | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactPath 'source-manifest.json')
} finally { Pop-Location }
