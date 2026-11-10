# OAP scheduling study: the running client's threads as Windows schedules them.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\performance\tools\oap_thread_prio.ps1 [-top 4] [-out file]
#
# jstack names each Java thread with its native id (nid); Get-Process lists the same native threads with the Windows
# base and current priority and CPU time. Joined, they show what priority Windows really gives the Render thread, the
# Server thread, the Ostinato workers and the GC threads, and (with -top) the top stack frames of each Ostinato thread.
# Read-only: attaches jstack for a moment, changes nothing.
param([int]$top = 4, [string]$out = "", [int]$procId = 0)
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
$cl = if ($procId) { Get-Process -Id $procId } else { Get-Process java -EA 0 | ? MainWindowTitle -match Minecraft | Select-Object -First 1 }
if (-not $cl) { "NO CLIENT"; exit 1 }
$dump = & "$jdk\bin\jstack.exe" $cl.Id 2>$null
$os = @{}
foreach ($t in (Get-Process -Id $cl.Id).Threads) { $os[[int]$t.Id] = $t }
$lines = @("pid=$($cl.Id) at $(Get-Date -Format s) affinity=0x$('{0:X}' -f [int64]$cl.ProcessorAffinity) class=$($cl.PriorityClass)")
$cur = $null; $frames = 0
foreach ($l in $dump) {
    if ($l -match '^"([^"]+)".* nid=(0x[0-9a-f]+|\d+)') {
        $name = $Matches[1]; $n = $Matches[2]
        $nid = if ($n.StartsWith('0x')) { [Convert]::ToInt32($n.Substring(2), 16) } else { [int]$n }
        $jp = if ($l -match ' prio=(\d+)') { $Matches[1] } else { "-" }
        $jp += "/os" + $(if ($l -match ' os_prio=(-?\d+)') { $Matches[1] } else { "-" })
        $t = $os[$nid]
        $keep = $name -match 'Ostinato|Render thread|Server thread|GC Thread|G1 |physics|C2 Compiler|C1 Compiler'
        $cur = $null
        if ($keep -and $t) {
            $lines += ("{0,-34} java={1,-6} base={2,-2} cur={3,-2} cpu={4,8:N1}s state={5}" -f $name, $jp, $t.BasePriority, $t.CurrentPriority,
                       $t.TotalProcessorTime.TotalSeconds, $t.ThreadState)
            if ($name -match 'Ostinato') { $cur = $name; $frames = 0 }
        }
    } elseif ($cur -and $top -gt 0 -and $l -match '^\s+at (.+)$' -and $frames -lt $top) {
        $lines += "      at $($Matches[1])"; $frames++
    }
}
if ($out) { $lines | Add-Content $out }
$lines
