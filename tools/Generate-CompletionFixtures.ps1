param(
    [Parameter(Mandatory=$true)][string]$TestDomain,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
if($TestDomain -notmatch '^[a-z0-9.-]+$' -or $TestDomain.Length -gt 253){throw 'TestDomain 必须是小写公共 HTTPS 域名，不含协议、端口或路径。'}
$repoDirectory=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$platformDirectory=Join-Path $repoDirectory 'platforms/1.21.1-neoforge'
if(-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))){throw 'JdkHome 下找不到 bin/java.exe，请提供 Java 21 目录。'}
if(-not $OutputDirectory){$OutputDirectory=Join-Path $repoDirectory 'build/completion-fixtures'}
$previousJava=$env:JAVA_HOME
$previousDomain=$env:MCPHONE_FIXTURE_HOST
$previousGradle=$env:GRADLE_USER_HOME
try{
    $env:JAVA_HOME=$JdkHome
    $env:MCPHONE_FIXTURE_HOST=$TestDomain
    $localCache=Join-Path (Split-Path $repoDirectory -Parent) '.gradle-home'
    if(Test-Path -LiteralPath $localCache){$env:GRADLE_USER_HOME=$localCache}
    Push-Location $platformDirectory
    try{
        & '.\gradlew.bat' --offline --no-daemon --max-workers=1 assertTestCompletionFixturesTest --rerun-tasks
        if($LASTEXITCODE -ne 0){throw '样例生成/验证失败，请保留输出并检查 Java 与依赖缓存。'}
    }finally{Pop-Location}
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $sourceDirectory=Join-Path $platformDirectory 'build/completion-fixtures'
    foreach($file in Get-ChildItem -LiteralPath $sourceDirectory -File){Copy-Item -LiteralPath $file.FullName -Destination $OutputDirectory -Force}
    Copy-Item -LiteralPath (Join-Path $repoDirectory 'demo-apps/completion/frontend/icon.png') -Destination (Join-Path $OutputDirectory 'icon.png') -Force
    Write-Output "已生成并验证样例：$OutputDirectory。测试私钥仅在本次进程内，不会交付或用于正式作品。"
}finally{
    $env:JAVA_HOME=$previousJava
    $env:MCPHONE_FIXTURE_HOST=$previousDomain
    $env:GRADLE_USER_HOME=$previousGradle
}
