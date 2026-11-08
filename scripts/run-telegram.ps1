param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$configuration = Join-Path $repository '.local/telegram.properties'
$jar = Join-Path $repository 'target/video-unique-service-0.1.0-SNAPSHOT.jar'
if (!(Test-Path $configuration)) { throw 'Run scripts/configure-telegram.ps1 first.' }
if (!(Test-Path $jar)) { throw 'Build the project first: mvn verify' }
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
Push-Location $repository
try {
    & $javaExecutable '-Djava.net.useSystemProxies=true' -jar $jar "--spring.config.additional-location=file:$($configuration.Replace('\','/'))" "--server.port=$Port"
    $result = $LASTEXITCODE
} finally { Pop-Location }
exit $result
