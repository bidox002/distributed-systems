param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath
)

if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    throw "Configuration file was not found: $ConfigPath"
}

$settings = @{}
Get-Content -LiteralPath $ConfigPath | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $parts = $line.Split('=', 2)
        if ($parts.Count -eq 2) {
            $settings[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
}

0..9 | ForEach-Object {
    $nodeId = $_
    $hostName = $settings["node.$nodeId.host"]
    $port = $settings["node.$nodeId.port"]
    if (-not $hostName -or -not $port) {
        throw "Missing host or port for node $nodeId"
    }

    try {
        $response = Invoke-RestMethod -Uri "http://${hostName}:$port/api/health" -TimeoutSec 5
        [PSCustomObject]@{ Node = $nodeId; Address = "${hostName}:$port"; Status = $response.status }
    } catch {
        [PSCustomObject]@{ Node = $nodeId; Address = "${hostName}:$port"; Status = "UNREACHABLE" }
    }
} | Format-Table -AutoSize
