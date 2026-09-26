<#
  Capture the Ncrust window at a fixed size, optionally clicking inside it first.

  Why a fixed size: a restored UWP window can be tiny (514x359 was seen) and a
  "maximize" request is not always honoured, so screenshots end up useless.
  SetWindowPos with an explicit rectangle is reliable.

  Why exact title + class match: terminal windows often carry "Ncrust" in their
  title. Only the ApplicationFrameWindow titled exactly "Ncrust" is ours.

  Usage:
    powershell -File Shot.ps1 -Out shot.png                       # 1280x800
    powershell -File Shot.ps1 -Out shot.png -Width 900 -Height 640
    powershell -File Shot.ps1 -Out shot.png -Click 24,56          # click (window coords) first
    powershell -File Shot.ps1 -Out shot.png -Click 24,56 -Click 300,200 -SettleMs 1500
    powershell -File Shot.ps1 -Out shot.png -Keys "{ESC}"         # SendKeys syntax, after clicks

  Coordinates are physical pixels relative to the window's top-left corner
  (the same pixels you see in the resulting PNG).
#>
param(
  [Parameter(Mandatory=$true)][string]$Out,
  [int]$Width = 1280,
  [int]$Height = 800,
  [string[]]$Click = @(),
  [string]$Keys = "",
  [int]$SettleMs = 1200
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class NcrustWin {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int m);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int m);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  public static IntPtr Find() {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      var t = new StringBuilder(256); GetWindowTextW(h, t, 256);
      var c = new StringBuilder(256); GetClassNameW(h, c, 256);
      if (t.ToString() == "Ncrust" && c.ToString() == "ApplicationFrameWindow") { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
"@

[NcrustWin]::SetProcessDPIAware() | Out-Null
$h = [NcrustWin]::Find()
if ($h -eq [IntPtr]::Zero) { Write-Error "Ncrust window not found"; exit 1 }

$TOPMOST = [IntPtr](-1); $NOTOPMOST = [IntPtr](-2)
[NcrustWin]::ShowWindow($h, 9) | Out-Null   # SW_RESTORE
[NcrustWin]::SetWindowPos($h, $TOPMOST, 0, 0, $Width, $Height, 0x0040) | Out-Null
[NcrustWin]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 600

$r = New-Object NcrustWin+RECT
[NcrustWin]::GetWindowRect($h, [ref]$r) | Out-Null

foreach ($c in $Click) {
  $xy = $c.Split(',')
  $x = $r.L + [int]$xy[0]; $y = $r.T + [int]$xy[1]
  [NcrustWin]::SetCursorPos($x, $y) | Out-Null
  Start-Sleep -Milliseconds 80
  [NcrustWin]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)  # LEFTDOWN
  [NcrustWin]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)  # LEFTUP
  Start-Sleep -Milliseconds 500
}
if ($Keys -ne "") { [System.Windows.Forms.SendKeys]::SendWait($Keys); }

Start-Sleep -Milliseconds $SettleMs
[NcrustWin]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.R - $r.L; $hh = $r.B - $r.T
$bmp = New-Object System.Drawing.Bitmap $w, $hh
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size $w, $hh))
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
[NcrustWin]::SetWindowPos($h, $NOTOPMOST, 0, 0, 0, 0, 0x0003) | Out-Null
"saved $Out (${w}x${hh})"
