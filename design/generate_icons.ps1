Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Stop"

$designDir = "E:\Android Projects\EPSSGTracker\design"
$resDir = "E:\Android Projects\EPSSGTracker\app\src\main\res"

function New-TransparentBitmap([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.Dispose()
    return $bmp
}

function Set-HighQuality([System.Drawing.Graphics]$g) {
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
}

# ---------------------------------------------------------------------------
# Step 1: build a clean 811x811 "icon art" square from SPT_Icon.png:
#   crop the black card's bounding box, then blacken the 4 rounded-corner
#   leak zones (where the mockup's outer gray background shows through the
#   card's rounded corners) using the measured corner radius.
# ---------------------------------------------------------------------------
$srcIcon = [System.Drawing.Bitmap]::FromFile("$designDir\SPT_Icon.png")
$cardX0 = 106; $cardY0 = 106; $cardX1 = 917; $cardY1 = 917
$cardSize = $cardX1 - $cardX0  # 811
$R = 120  # corner radius, measured

$iconArt = New-Object System.Drawing.Bitmap $cardSize, $cardSize, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
for ($ly = 0; $ly -lt $cardSize; $ly++) {
    for ($lx = 0; $lx -lt $cardSize; $lx++) {
        $inCornerBox = $false
        $cx = 0; $cy = 0
        if ($lx -lt $R -and $ly -lt $R) { $inCornerBox = $true; $cx = $R; $cy = $R }
        elseif ($lx -ge ($cardSize - $R) -and $ly -lt $R) { $inCornerBox = $true; $cx = $cardSize - 1 - $R; $cy = $R }
        elseif ($lx -lt $R -and $ly -ge ($cardSize - $R)) { $inCornerBox = $true; $cx = $R; $cy = $cardSize - 1 - $R }
        elseif ($lx -ge ($cardSize - $R) -and $ly -ge ($cardSize - $R)) { $inCornerBox = $true; $cx = $cardSize - 1 - $R; $cy = $cardSize - 1 - $R }

        if ($inCornerBox) {
            $dx = $lx - $cx; $dy = $ly - $cy
            $dist = [math]::Sqrt($dx*$dx + $dy*$dy)
            if ($dist -gt $R) {
                $iconArt.SetPixel($lx, $ly, [System.Drawing.Color]::FromArgb(255, 0, 0, 0))
                continue
            }
        }
        $p = $srcIcon.GetPixel($cardX0 + $lx, $cardY0 + $ly)
        $iconArt.SetPixel($lx, $ly, [System.Drawing.Color]::FromArgb(255, $p.R, $p.G, $p.B))
    }
}
$srcIcon.Dispose()
$iconArt.Save("$designDir\_iconArt.png", [System.Drawing.Imaging.ImageFormat]::Png)
Write-Output "iconArt built: $($iconArt.Width)x$($iconArt.Height)"

# ---------------------------------------------------------------------------
# Step 2: adaptive icon background (solid black) + foreground (iconArt inset
# into the ~66dp/108dp safe zone, centered, transparent surround)
# ---------------------------------------------------------------------------
$adaptiveSize = 432  # 108dp @ xxxhdpi (4x)
$safeFrac = 0.62

$bg = New-TransparentBitmap $adaptiveSize $adaptiveSize
$gbg = [System.Drawing.Graphics]::FromImage($bg)
$gbg.Clear([System.Drawing.Color]::Black)
$gbg.Dispose()
$bg.Save("$resDir\drawable-xxxhdpi\ic_launcher_background.png", [System.Drawing.Imaging.ImageFormat]::Png)

$fg = New-TransparentBitmap $adaptiveSize $adaptiveSize
$gfg = [System.Drawing.Graphics]::FromImage($fg)
Set-HighQuality $gfg
$contentSize = [int]($adaptiveSize * $safeFrac)
$offset = [int](($adaptiveSize - $contentSize) / 2)
$gfg.DrawImage($iconArt, $offset, $offset, $contentSize, $contentSize)
$gfg.Dispose()
$fg.Save("$resDir\drawable-xxxhdpi\ic_launcher_foreground.png", [System.Drawing.Imaging.ImageFormat]::Png)
Write-Output "Adaptive icon background/foreground written"

# ---------------------------------------------------------------------------
# Step 3: legacy per-density launcher icons (square + round), full bleed with
# a small margin, for pre-adaptive-icon fallback / non-adaptive launchers
# ---------------------------------------------------------------------------
$densities = @{
    "mdpi" = 48
    "hdpi" = 72
    "xhdpi" = 96
    "xxhdpi" = 144
    "xxxhdpi" = 192
}

foreach ($d in $densities.Keys) {
    $size = $densities[$d]
    $margin = [int]($size * 0.06)
    $inner = $size - 2 * $margin

    # Square legacy icon: black canvas + iconArt inset slightly
    $sq = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gsq = [System.Drawing.Graphics]::FromImage($sq)
    Set-HighQuality $gsq
    $gsq.Clear([System.Drawing.Color]::Black)
    $gsq.DrawImage($iconArt, $margin, $margin, $inner, $inner)
    $gsq.Dispose()
    $outDir = "$resDir\mipmap-$d"
    if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
    Remove-Item -Path "$outDir\ic_launcher.webp" -ErrorAction SilentlyContinue
    Remove-Item -Path "$outDir\ic_launcher_round.webp" -ErrorAction SilentlyContinue
    $sq.Save("$outDir\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)

    # Round variant: same content, circular clip
    $rnd = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $grnd = [System.Drawing.Graphics]::FromImage($rnd)
    Set-HighQuality $grnd
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $size, $size)
    $grnd.SetClip($path)
    $grnd.Clear([System.Drawing.Color]::Black)
    $grnd.DrawImage($iconArt, $margin, $margin, $inner, $inner)
    $grnd.Dispose()
    $rnd.Save("$outDir\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)

    $sq.Dispose(); $rnd.Dispose()
    Write-Output "Legacy icons written for $d ($size px)"
}

# ---------------------------------------------------------------------------
# Step 4: splash screen assets
#   - splash_icon.png: iconArt inset into a safe zone (the platform auto-zooms
#     splash icons the same way it does adaptive launcher icons, so a
#     full-bleed image gets its edges cropped off - same safeFrac as the
#     launcher foreground avoids that)
#   - splash_branding.png: tagline crop from SPT_SplashScreen.png, padded to
#     the platform-mandated 5:2 aspect ratio (a mismatched aspect gets
#     non-uniformly stretched to fill the branding slot instead of letterboxed)
# ---------------------------------------------------------------------------
$splashIconSize = 480
$splashIcon = New-TransparentBitmap $splashIconSize $splashIconSize
$gsi = [System.Drawing.Graphics]::FromImage($splashIcon)
Set-HighQuality $gsi
$splashContentSize = [int]($splashIconSize * $safeFrac)
$splashOffset = [int](($splashIconSize - $splashContentSize) / 2)
$gsi.DrawImage($iconArt, $splashOffset, $splashOffset, $splashContentSize, $splashContentSize)
$gsi.Dispose()
if (-not (Test-Path "$resDir\drawable")) { New-Item -ItemType Directory -Path "$resDir\drawable" | Out-Null }
$splashIcon.Save("$resDir\drawable\splash_icon.png", [System.Drawing.Imaging.ImageFormat]::Png)
$splashIcon.Dispose()

$srcSplash = [System.Drawing.Bitmap]::FromFile("$designDir\SPT_SplashScreen.png")
# 680x272 = 5:2 aspect ratio. Text band is ~1016-1046; the "SPT" emblem text
# ends around y=982, so top padding is kept tight (below that) and the rest
# of the required height is padded below, where the source is plain black.
$tagX0 = 44; $tagY0 = 1000; $tagX1 = 724; $tagY1 = 1272
$tagW = $tagX1 - $tagX0; $tagH = $tagY1 - $tagY0
$tagCrop = New-Object System.Drawing.Bitmap $tagW, $tagH, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gtag = [System.Drawing.Graphics]::FromImage($tagCrop)
$gtag.DrawImage($srcSplash, (New-Object System.Drawing.Rectangle(0,0,$tagW,$tagH)), (New-Object System.Drawing.Rectangle($tagX0,$tagY0,$tagW,$tagH)), [System.Drawing.GraphicsUnit]::Pixel)
$gtag.Dispose()
$tagCrop.Save("$resDir\drawable\splash_branding.png", [System.Drawing.Imaging.ImageFormat]::Png)
$tagCrop.Dispose()
$srcSplash.Dispose()

Write-Output "Splash assets written"

$iconArt.Dispose()
Write-Output "DONE"
