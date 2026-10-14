Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
public class SoftClick {
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);
  public struct RECT { public int L; public int T; public int R; public int B; }
  public static void Click(IntPtr h, int x, int y) {
    IntPtr lp = new IntPtr((y << 16) | (x & 0xFFFF));
    PostMessage(h, 0x0200, IntPtr.Zero, lp); Thread.Sleep(30);
    PostMessage(h, 0x0201, new IntPtr(1), lp); Thread.Sleep(40);
    PostMessage(h, 0x0202, IntPtr.Zero, lp);
  }
}
"@
$proc = Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object { $_.CommandLine -match 'fabric.classPathGroups' } | Select-Object -First 1
if (-not $proc) { Write-Output "NOCLIENT"; exit 1 }
$p = Get-Process -Id $proc.ProcessId
$h = $p.MainWindowHandle
$r = New-Object SoftClick+RECT
[SoftClick]::GetClientRect($h, [ref]$r) | Out-Null
$x = [int](427.0 * $r.R / 854); $y = [int](420.0 * $r.B / 480)
Write-Output "client=$($r.R)x$($r.B) click $x,$y"
[SoftClick]::Click($h, $x, $y)
