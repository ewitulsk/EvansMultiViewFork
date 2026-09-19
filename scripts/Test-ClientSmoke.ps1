param([switch]$PrepareOnly, [string]$ArtifactPath, [string]$Scenario = 'smoke', [string]$ReplaySources, [int]$PlaySeconds = 30)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ArtifactPath) { $ArtifactPath = Join-Path $projectRoot ('artifacts/client-' + $Scenario + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
$artifactPath = [IO.Path]::GetFullPath($ArtifactPath)
if(-not $artifactPath.StartsWith([IO.Path]::GetFullPath((Join-Path $projectRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Client test artifacts must remain project-owned.'}
if(Test-Path -LiteralPath $artifactPath){throw 'Refusing to overwrite client test evidence.'}
$clientPath = Join-Path $artifactPath 'client'
$null = New-Item -ItemType Directory -Path $clientPath
& "$PSScriptRoot/Write-TestManifest.ps1" -ArtifactPath $artifactPath
@('fullscreen:false', 'pauseOnLostFocus:false', 'narrator:0', 'onboardAccessibility:false',
  'soundCategory_master:0.0', 'maxFps:30', 'enableVsync:false', 'renderDistance:6',
  'simulationDistance:5', 'skipMultiplayerWarning:true') | Set-Content (Join-Path $clientPath 'options.txt')

# Source replay zips: merge-family scenarios get a TestHarness config file; the ui
# scenario gets the zips copied into the client's flashback/replays folder so the
# SelectReplayScreen lists them.
$sourceList = @()
if ($ReplaySources) {
    $sourceList = @($ReplaySources -split ';' | Where-Object { $_ -and $_.Trim() } | ForEach-Object {
        $p = [IO.Path]::GetFullPath($_.Trim())
        if (-not (Test-Path -LiteralPath $p)) { throw "Replay source not found: $p" }
        $p
    })
}
if (($Scenario -eq 'merge' -or $Scenario -eq 'mergeplay' -or $Scenario -eq 'merge8') -and $sourceList.Count -lt 2) {
    throw "Scenario '$Scenario' requires -ReplaySources with at least 2 replay zips (';'-separated)."
}
if ($sourceList.Count -gt 0) {
    if ($Scenario -eq 'ui') {
        $replaysDir = Join-Path $clientPath 'flashback/replays'
        $null = New-Item -ItemType Directory -Force -Path $replaysDir
        foreach ($src in $sourceList) { Copy-Item -LiteralPath $src -Destination $replaysDir }
    } else {
        # Forward slashes keep the JSON unambiguous on any platform; Path.resolve handles them.
        $jsonSources = @($sourceList | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' })
        $config = '{ "mode": "merge", "sources": [' + ($jsonSources -join ', ') +
            '], "output": "harness_' + $Scenario + '", "playSeconds": ' + $PlaySeconds +
            ', "playTimeoutSeconds": 900, "maxRunMinutes": 60 }'
        Set-Content (Join-Path $clientPath '.multiview-test.json') $config
    }
}

$metadata = [ordered]@{ status='PREPARING'; startedUtc=[DateTime]::UtcNow.ToString('o'); desktopInput=$false; scenario=$Scenario; command="exportTestLaunches -PmultiviewClientScenario=$Scenario" }
$metadata | ConvertTo-Json | Set-Content (Join-Path $artifactPath 'result.json')
$ownedProcesses = [Collections.Generic.List[object]]::new()
function Start-TestProcess([string]$Side) {
    $spec = Get-Content (Join-Path $artifactPath "$Side-launch.json") -Raw | ConvertFrom-Json
    # DevAuth would attempt an interactive login; the hidden client uses the default offline profile.
    $args_ = [Collections.Generic.List[string]]::new()
    for ($i = 1; $i -lt $spec.command.Count; $i++) {
        $arg = [string]$spec.command[$i]
        if ($arg -eq '-cp' -and $i + 1 -lt $spec.command.Count) {
            $args_.Add('-cp')
            $filtered = ([string]$spec.command[$i + 1] -split [IO.Path]::PathSeparator) | Where-Object { $_ -notmatch 'DevAuth' }
            $args_.Add($filtered -join [IO.Path]::PathSeparator)
            $i++
            continue
        }
        $args_.Add($arg)
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $spec.command[0]
    $startInfo.WorkingDirectory = $spec.directory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.Arguments = ($args_ | ForEach-Object {
        $value = [string]$_
        if ($value -notmatch '[ \t"]') { return $value }
        '"' + ($value -replace '(\\+)"', '$1$1\"' -replace '(\\+)$', '$1$1' -replace '"', '\"') + '"'
    }) -join ' '
    foreach ($property in $spec.environment.PSObject.Properties) { $startInfo.Environment[$property.Name] = [string]$property.Value }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $null = $process.Start()
    $item = @{ side=$Side; process=$process; stdout=$process.StandardOutput.ReadToEndAsync(); stderr=$process.StandardError.ReadToEndAsync() }
    $ownedProcesses.Add($item)
    return $item
}
try {
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'  # javac/gradle warnings on stderr must not abort the run
    try {
        & (Join-Path $projectRoot 'gradlew.bat') exportTestLaunches "-PmultiviewClientScenario=$Scenario" "-PmultiviewClientRunDir=$clientPath" "-PmultiviewLaunchExportDir=$artifactPath" --console=plain *>&1 |
            Tee-Object -FilePath (Join-Path $artifactPath 'export.log')
    } finally {
        $ErrorActionPreference = $previousEap
    }
    if ($LASTEXITCODE -ne 0) { throw "exportTestLaunches failed: $LASTEXITCODE" }
    if ($PrepareOnly) { $metadata.status='PREPARED'; return }
    $metadata.status='RUNNING'
    $metadata | ConvertTo-Json | Set-Content (Join-Path $artifactPath 'result.json')
    $client = Start-TestProcess 'client'
    $timeoutSeconds = switch ($Scenario) { 'merge8' { 5400 } 'merge' { 1800 } 'mergeplay' { 1800 } 'ui' { 300 } default { 240 } }
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while (!$client.process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if (!$client.process.HasExited) { throw 'Hidden client timed out' }
    if ($client.process.ExitCode -ne 0) { throw "Hidden client failed: $($client.process.ExitCode)" }
    $clientLog = Get-Content (Join-Path $clientPath 'logs/latest.log') -Raw
    if ($Scenario -eq 'merge') {
        if ($clientLog -notmatch 'HIDDEN_MERGE_RESULT verdict=PASS') { throw 'Missing merge verdict PASS' }
    } elseif ($Scenario -eq 'mergeplay' -or $Scenario -eq 'merge8') {
        if ($clientLog -notmatch 'HIDDEN_MERGE_RESULT verdict=PASS') { throw 'Missing merge verdict PASS' }
        if ($clientLog -notmatch 'HIDDEN_MERGEPLAY_ENTITY') { throw 'Missing secondary-player entity evidence' }
    } elseif ($Scenario -eq 'ui') {
        if ($clientLog -notmatch 'HIDDEN_UI_PASS') { throw 'Missing UI scenario completion' }
        if ($clientLog -notmatch 'HIDDEN_UI_BUTTON found') { throw 'Merge button was never injected' }
    }
    if ($clientLog -notmatch "HIDDEN_CLIENT_PASS scenario=$Scenario") { throw 'Missing hidden-client completion' }
    if (!(Test-Path -LiteralPath (Join-Path $clientPath "hidden-$Scenario.png"))) { throw 'Missing client rendering evidence' }
    $metadata.status='PASS'
} catch {
    $metadata.status='FAIL'; $metadata.error=$_.Exception.Message; throw
} finally {
    foreach ($item in $ownedProcesses) {
        if (!$item.process.HasExited) { $item.process.Kill($true); $item.process.WaitForExit() }
        $item.stdout.GetAwaiter().GetResult() | Set-Content (Join-Path $artifactPath ($item.side + '-stdout.log'))
        $item.stderr.GetAwaiter().GetResult() | Set-Content (Join-Path $artifactPath ($item.side + '-stderr.log'))
        $item.process.Dispose()
    }
    $metadata.finishedUtc=[DateTime]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json | Set-Content (Join-Path $artifactPath 'result.json')
    Write-Output "Hidden client artifacts: $artifactPath"
}
