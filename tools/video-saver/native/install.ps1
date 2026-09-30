param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-p]{32}$')][string]$ExtensionId,
    [string]$OutputDirectory = "$env:USERPROFILE\Downloads\Pickwick"
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$python = (Get-Command python -ErrorAction Stop).Source
$ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source
$ffprobe = Join-Path (Split-Path $ffmpeg) 'ffprobe.exe'
if (-not (Test-Path -LiteralPath $ffprobe)) { throw 'ffprobe must be installed beside ffmpeg.' }
$venv = Join-Path $root '.venv'
if (-not (Test-Path -LiteralPath (Join-Path $venv 'Scripts\python.exe'))) {
    & $python -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create helper environment.' }
}
$runtime = Join-Path $venv 'Scripts\python.exe'
& $runtime -m pip install --disable-pip-version-check 'yt-dlp==2026.8.19'
if ($LASTEXITCODE -ne 0) { throw 'Could not install yt-dlp.' }
$config = @{ extension_id=$ExtensionId; output=[IO.Path]::GetFullPath($OutputDirectory); ffmpeg=$ffmpeg; ffprobe=$ffprobe }
$config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'config.json') -Encoding UTF8
$launcher = Join-Path $root 'launch.cmd'
@('@echo off', ('"{0}" "{1}" %*' -f $runtime, (Join-Path $root 'host.py'))) | Set-Content -LiteralPath $launcher -Encoding ASCII
$manifestPath = Join-Path $root 'host.json'
$hostManifest = @{
    name='io.pickwick.video_saver'; description='Pickwick local video merger'; path=$launcher;
    type='stdio'; allowed_origins=@("chrome-extension://$ExtensionId/")
} | ConvertTo-Json
[IO.File]::WriteAllText($manifestPath, $hostManifest, (New-Object Text.UTF8Encoding($false)))
$registries = @(
    'HKCU:\Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\io.pickwick.video_saver',
    'HKCU:\Software\Google\Chrome\NativeMessagingHosts\io.pickwick.video_saver'
)
foreach ($registry in $registries) {
    New-Item -Path $registry -Force | Out-Null
    Set-Item -Path $registry -Value $manifestPath
}
Write-Output "Helper installed for $ExtensionId. Output: $OutputDirectory"
