<#
  Generate the Windows app icons: full-bleed green square with a vinyl record
  (grooves + white outer ring + label + play notch). Geometry follows the
  Android ic_launcher.xml 192-unit viewport; colour is the default accent #1DB954.

  Every asset is a full square/rectangle of green -- no transparent corners,
  no round plate. Grooves are only drawn when they stay >= ~3px apart, so small
  sizes (taskbar 16/24/32) show a clean ring instead of noise.

  Usage (run once, commit the PNGs; the build does not regenerate them):
    powershell -ExecutionPolicy Bypass -File MakeIcons.ps1 -OutDir ..\..\src\Ncrust.App\Assets
#>
param([Parameter(Mandatory=$true)][string]$OutDir)

Add-Type -AssemblyName System.Drawing
$green = [System.Drawing.Color]::FromArgb(255, 0x1D, 0xB9, 0x54)
$white = [System.Drawing.Color]::White

function Draw-Record($g, [double]$cx, [double]$cy, [double]$k) {
    # k = pixels per viewport unit; (cx, cy) = centre of the 192-unit record.
    # Grooves: concentric thin rings between the label (r=20) and the outer ring (r=64).
    $step = [Math]::Max(5.0, 3.0 / $k)
    if ($k * 64 -ge 20) {
        $r = 28.0
        $i = 0
        while ($r -le 57.0) {
            $alpha = if ($i % 2 -eq 0) { 110 } else { 60 }
            $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb($alpha, 255, 255, 255)), ([float][Math]::Max(1.0, 1.4 * $k))
            $g.DrawEllipse($pen, [float]($cx - $r*$k), [float]($cy - $r*$k), [float](2*$r*$k), [float](2*$r*$k))
            $pen.Dispose()
            $r += $step
            $i++
        }
    }

    # Outer ring (Android: r=64, stroke 12).
    $ring = New-Object System.Drawing.Pen $white, ([float](12*$k))
    $g.DrawEllipse($ring, [float]($cx - 64*$k), [float]($cy - 64*$k), [float](128*$k), [float](128*$k))
    $ring.Dispose()

    # Label + spindle hole.
    $wb = New-Object System.Drawing.SolidBrush $white
    $g.FillEllipse($wb, [float]($cx - 20*$k), [float]($cy - 20*$k), [float](40*$k), [float](40*$k))
    $gb = New-Object System.Drawing.SolidBrush $green
    $g.FillEllipse($gb, [float]($cx - 8*$k), [float]($cy - 8*$k), [float](16*$k), [float](16*$k))

    # Play notch (Android: M144,96 L128,80 L128,112 Z).
    $pts = @(
        (New-Object System.Drawing.PointF ([float]($cx + 48*$k)), ([float]$cy)),
        (New-Object System.Drawing.PointF ([float]($cx + 32*$k)), ([float]($cy - 16*$k))),
        (New-Object System.Drawing.PointF ([float]($cx + 32*$k)), ([float]($cy + 16*$k)))
    )
    $g.FillPolygon($wb, $pts)
    $wb.Dispose(); $gb.Dispose()
}

# fraction = record's outer ring diameter (128 units + stroke) relative to the shorter side.
function New-Icon([string]$name, [int]$w, [int]$h, [double]$fraction) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear($green)
    $k = ([Math]::Min($w, $h) * $fraction) / 140.0
    Draw-Record $g ($w / 2.0) ($h / 2.0) $k
    $bmp.Save((Join-Path $OutDir $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

New-Item -ItemType Directory -Force $OutDir | Out-Null

# App list / taskbar / title bar. targetsize-* are used unplated, so they must be
# full-bleed green themselves (that is the whole point: the icon IS a green square).
foreach ($s in @(16, 24, 32, 48, 256)) {
    New-Icon "Square44x44Logo.targetsize-$s.png" $s $s 0.78
    New-Icon "Square44x44Logo.targetsize-${s}_altform-unplated.png" $s $s 0.78
}
foreach ($pair in @(@(100, 44), @(125, 55), @(150, 66), @(200, 88), @(400, 176))) {
    New-Icon "Square44x44Logo.scale-$($pair[0]).png" $pair[1] $pair[1] 0.78
}

# Start tiles.
foreach ($pair in @(@(100, 71), @(200, 142), @(400, 284))) {
    New-Icon "Square71x71Logo.scale-$($pair[0]).png" $pair[1] $pair[1] 0.62
}
foreach ($pair in @(@(100, 150), @(125, 188), @(150, 225), @(200, 300), @(400, 600))) {
    New-Icon "Square150x150Logo.scale-$($pair[0]).png" $pair[1] $pair[1] 0.56
}
foreach ($pair in @(@(100, 310, 150), @(200, 620, 300), @(400, 1240, 600))) {
    New-Icon "Wide310x150Logo.scale-$($pair[0]).png" $pair[1] $pair[2] 0.56
}
foreach ($pair in @(@(100, 310), @(200, 620))) {
    New-Icon "Square310x310Logo.scale-$($pair[0]).png" $pair[1] $pair[1] 0.52
}

# Store logo (50x50 at scale-100) and splash screen (620x300 at scale-100).
foreach ($pair in @(@(100, 50), @(200, 100), @(400, 200))) {
    New-Icon "StoreLogo.scale-$($pair[0]).png" $pair[1] $pair[1] 0.78
}
foreach ($pair in @(@(100, 620, 300), @(200, 1240, 600))) {
    New-Icon "SplashScreen.scale-$($pair[0]).png" $pair[1] $pair[2] 0.52
}

Get-ChildItem $OutDir -Filter *.png | Measure-Object | ForEach-Object { "generated $($_.Count) files" }
