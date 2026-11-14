# FzClient: now playing from Windows' media controls (what the volume flyout shows), for the music HUD.
# Started by MediaSession.java with %PID% (the game) and %FILTER% (0 any app, 1 YouTube Music, 2 Apple Music).
# Prints one line a second:  S status posMs endMs ageMs app title artist album   (texts in Base64 UTF-8, "-" = empty)
# PowerShell names ignore case: every variable here has its own name ($P and $p would be the same one).
#                            N  (no matching app)     X  (media controls not available)
# Reads commands, one per line: P play/pause, N next, B previous. Quits with the game or when its input closes.
$ErrorActionPreference = 'SilentlyContinue'
$gamePid = %PID%
$filter = %FILTER%
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$at = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$type) {
    if ($op -eq $null) { return $null }
    $task = $at.MakeGenericMethod($type).Invoke($null, @($op))
    if (-not $task.Wait(3000)) { return $null }
    $task.Result
}
$ManagerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$PropsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
$m = Await ($ManagerType::RequestAsync()) $ManagerType
if (-not $m) { [Console]::Out.WriteLine('X'); [Console]::Out.Flush(); exit }
# browsers by name; Firefox shows up as a 16-digit hex id
$browsers = 'chrome|msedge|firefox|brave|opera|vivaldi|^[0-9A-F]{16}$'

function Pick {
    $all = @($m.GetSessions())
    if ($filter -eq 1) {
        # the YouTube Music apps first; YouTube Music opened in a browser (or installed from it) after that
        $apps = @($all | ? { $_.SourceAppUserModelId -match 'youtube|ytm' })
        if ($apps.Count -eq 0) {
            # in a browser: only what has an album (YouTube Music gives one; plain videos, Twitch, Netflix don't)
            $apps = @($all | ? { $_.SourceAppUserModelId -match $browsers } | ? {
                $props = Await ($_.TryGetMediaPropertiesAsync()) $PropsType
                $props -and $props.AlbumTitle })
        }
    } elseif ($filter -eq 2) {
        $apps = @($all | ? { $_.SourceAppUserModelId -match 'applemusic|appleinc|itunes' })
    } else {
        $apps = $all
    }
    $playing = @($apps | ? { [int]$_.GetPlaybackInfo().PlaybackStatus -eq 4 })
    if ($playing.Count -gt 0) { return $playing[0] }
    if ($apps.Count -gt 0) { return $apps[0] }
    return $null
}

function B64($s) {
    if ($s -eq $null -or [string]$s -eq '') { return '-' }
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$s))
}

$in = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
$cmd = $in.ReadLineAsync()
while ($true) {
    if (-not (Get-Process -Id $gamePid)) { exit }
    if ($cmd.Wait(1000)) {
        $c = $cmd.Result
        if ($c -eq $null) { exit }
        $s = Pick
        if ($s) {
            $BoolType = [bool]
            if ($c -eq 'P') { Await ($s.TryTogglePlayPauseAsync()) $BoolType | Out-Null }
            elseif ($c -eq 'N') { Await ($s.TrySkipNextAsync()) $BoolType | Out-Null }
            elseif ($c -eq 'B') { Await ($s.TrySkipPreviousAsync()) $BoolType | Out-Null }
            Start-Sleep -Milliseconds 300
        }
        $cmd = $in.ReadLineAsync()
    }
    $s = Pick
    if ($s) {
        $media = Await ($s.TryGetMediaPropertiesAsync()) $PropsType
        $timeline = $s.GetTimelineProperties()
        $info = $s.GetPlaybackInfo()
        $age = [long]([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalMilliseconds
        [Console]::Out.WriteLine('S ' + [int]$info.PlaybackStatus + ' ' + [long]$timeline.Position.TotalMilliseconds + ' ' + [long]$timeline.EndTime.TotalMilliseconds + ' ' + $age + ' ' + (B64 $s.SourceAppUserModelId) + ' ' + (B64 $media.Title) + ' ' + (B64 $media.Artist) + ' ' + (B64 $media.AlbumTitle))
    } else {
        [Console]::Out.WriteLine('N')
    }
    [Console]::Out.Flush()
}
