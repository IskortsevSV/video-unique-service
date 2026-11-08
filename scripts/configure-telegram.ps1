$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$localDirectory = Join-Path $repository '.local'
New-Item -ItemType Directory -Force $localDirectory | Out-Null
$secret = Read-Host 'Paste the BotFather token (input is hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($token -notmatch '^[0-9]+:[A-Za-z0-9_-]+$') { throw 'Invalid token format' }
    $configuration = "telegram.enabled=true`ntelegram.token=$token`n"
    [IO.File]::WriteAllText((Join-Path $localDirectory 'telegram.properties'), $configuration)
    Write-Output 'Saved .local/telegram.properties. Keep this local file private; Git ignores it.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $token = $null
    $configuration = $null
    $secret.Dispose()
}
