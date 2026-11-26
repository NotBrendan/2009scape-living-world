# P38 L6 fsync hog (the design notes section 6 step 5). Farm only.
# usage: powershell.exe -NoProfile -File disk-hog.ps1 <dir> <seconds> <parallel>
# Each job loops: write 1 MB to its own file under <dir>, FileStream.Flush($true) (= FlushFileBuffers, a real device flush).
# Prints flushes and flushes/s per job and in total at the end; deletes its files.
param([Parameter(Mandatory=$true)][string]$Dir, [int]$Seconds = 300, [int]$Parallel = 4)
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$start = Get-Date
Write-Output ("disk-hog start {0} dir={1} seconds={2} parallel={3}" -f $start.ToString('s'), $Dir, $Seconds, $Parallel)
$jobs = 1..$Parallel | ForEach-Object {
    Start-Job -ArgumentList (Join-Path $Dir ("disk-hog-{0}.bin" -f $_)), $Seconds -ScriptBlock {
        param($file, $secs)
        $buf = New-Object byte[] 1048576; (New-Object Random).NextBytes($buf)
        $n = 0; $sw = [Diagnostics.Stopwatch]::StartNew()
        $fs = New-Object IO.FileStream($file, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            while ($sw.Elapsed.TotalSeconds -lt $secs) {
                if ($fs.Length -ge 64MB) { $fs.SetLength(0); $fs.Position = 0 }
                $fs.Write($buf, 0, $buf.Length); $fs.Flush($true); $n++
            }
        } finally { $fs.Close(); Remove-Item -Force $file -ErrorAction SilentlyContinue }
        [pscustomobject]@{ File = $file; Flushes = $n; Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1) }
    }
}
$res = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job
$total = 0
foreach ($r in $res) {
    $total += $r.Flushes
    Write-Output ("disk-hog job {0}: {1} flushes in {2} s = {3:N1}/s" -f (Split-Path $r.File -Leaf), $r.Flushes, $r.Seconds, ($r.Flushes / [math]::Max($r.Seconds, 0.1)))
}
$el = ((Get-Date) - $start).TotalSeconds
Write-Output ("disk-hog end {0} total {1} flushes = {2:N1}/s over {3:N0} s" -f (Get-Date).ToString('s'), $total, ($total / [math]::Max($el, 0.1)), $el)
