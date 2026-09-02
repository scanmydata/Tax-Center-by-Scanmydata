# tools/make-icons.ps1 — παράγει τα εικονίδια και το λογότυπο της εφαρμογής.
# =============================================================================
# Το λογότυπο είναι ένα φύλλο 2x2 με τέσσερις παραλλαγές:
#
#     πάνω-αριστερά   ανοιχτό φόντο, χωρίς σκιά   -> launcher icon + light mode
#     πάνω-δεξιά      ανοιχτό φόντο, με σκιά
#     κάτω-αριστερά   σκούρο φόντο, με λάμψη      -> dark mode
#     κάτω-δεξιά      σκούρο φόντο, έντονη λάμψη
#
# Κρατάμε τις δύο αριστερές: η δεξιά στήλη έχει σκιά που δεν ταιριάζει σε
# adaptive icon, όπου το σύστημα βάζει δική του.
#
# Χρήση:
#   powershell -ExecutionPolicy Bypass -File tools/make-icons.ps1 `
#       -Source "C:\...\λογότυπο.png"
#
# Γιατί PowerShell και όχι Node: το System.Drawing υπάρχει ήδη στα Windows, ενώ
# η αποκωδικοποίηση PNG στο Node θα ήθελε native dependency (sharp) για δουλειά
# που γίνεται μία φορά. Το Prosfora-APK κάνει το ίδιο με Python/Pillow.

param(
    [Parameter(Mandatory = $true)][string]$Source,
    [string]$ResDir = "app/src/main/res",
    # Πόσο πρέπει να διαφέρει ένα pixel από το φόντο για να μετρήσει ως σχήμα.
    # Το λογότυπο κάθεται σε πάνελ με αχνό περίγραμμα: με χαμηλό κατώφλι το
    # περίγραμμα μετρούσε ως περιεχόμενο και το σχήμα έβγαινε μικρό κι εκτός κέντρου.
    # Το 90 = άθροισμα διαφορών RGB, δηλαδή ~30 ανά κανάλι.
    [int]$Tolerance = 90,
    # Η σκοτεινή παραλλαγή έχει λάμψη γύρω από τον φάκελο, που σβήνει σταδιακά
    # μέχρι την άκρη του τεταρτημορίου. Με το κατώφλι του ανοιχτού, το bbox
    # έπιανε τη λάμψη και το σχήμα έβγαινε κομμένο στην κορυφή.
    [int]$DarkTolerance = 200
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { throw "Δεν βρέθηκε το λογότυπο: $Source" }
$src = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Source))
Write-Output "Πηγή: $($src.Width)x$($src.Height)"

$qw = [int]($src.Width / 2)
$qh = [int]($src.Height / 2)

function Get-Quadrant([int]$col, [int]$row) {
    # Οι συντεταγμένες υπολογίζονται ΠΡΙΝ. Μέσα σε New-Object Type(...) η
    # PowerShell 5.1 παρσάρει λάθος το '*' και ζητά op_Multiply σε Object[].
    $srcX = $col * $qw
    $srcY = $row * $qh
    $dest = New-Object System.Drawing.Rectangle -ArgumentList 0, 0, $qw, $qh
    $from = New-Object System.Drawing.Rectangle -ArgumentList $srcX, $srcY, $qw, $qh
    $bmp  = New-Object System.Drawing.Bitmap -ArgumentList $qw, $qh
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.DrawImage($src, $dest, $from, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    return $bmp
}

# Τετράγωνο bounding box του σχήματος. Το φόντο μαντεύεται από τη γωνία, ώστε
# να δουλεύει και σε ανοιχτό και σε σκούρο τεταρτημόριο με την ίδια λογική.
function Get-ContentBox([System.Drawing.Bitmap]$bmp, [int]$tol) {
    $bg = $bmp.GetPixel(2, 2)
    $minX = $bmp.Width; $minY = $bmp.Height; $maxX = 0; $maxY = 0
    for ($y = 0; $y -lt $bmp.Height; $y += 2) {
        for ($x = 0; $x -lt $bmp.Width; $x += 2) {
            $p = $bmp.GetPixel($x, $y)
            if ($p.A -lt 24) { continue }
            $d = [Math]::Abs($p.R - $bg.R) + [Math]::Abs($p.G - $bg.G) + [Math]::Abs($p.B - $bg.B)
            if ($d -gt $tol) {
                if ($x -lt $minX) { $minX = $x }
                if ($y -lt $minY) { $minY = $y }
                if ($x -gt $maxX) { $maxX = $x }
                if ($y -gt $maxY) { $maxY = $y }
            }
        }
    }
    if ($maxX -le $minX -or $maxY -le $minY) { throw "Δεν βρέθηκε σχήμα στο τεταρτημόριο." }

    $bw = $maxX - $minX + 1
    $bh = $maxY - $minY + 1
    $side = [Math]::Max($bw, $bh)
    $cx = $minX + $bw / 2
    $cy = $minY + $bh / 2
    $sx = [int][Math]::Max(0, $cx - $side / 2)
    $sy = [int][Math]::Max(0, $cy - $side / 2)
    $side = [int][Math]::Min($side, [Math]::Min($bmp.Width - $sx, $bmp.Height - $sy))
    return @{ X = $sx; Y = $sy; Side = $side; W = $bw; H = $bh }
}

function Write-Scaled([System.Drawing.Bitmap]$from, $box, [int]$canvas, [double]$fraction, [string]$outPath) {
    $bmp = New-Object System.Drawing.Bitmap($canvas, $canvas)
    $gr = [System.Drawing.Graphics]::FromImage($bmp)
    $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gr.Clear([System.Drawing.Color]::Transparent)
    $target = [int]($canvas * $fraction)
    $off = [int](($canvas - $target) / 2)
    $gr.DrawImage($from,
        (New-Object System.Drawing.Rectangle($off, $off, $target, $target)),
        (New-Object System.Drawing.Rectangle($box.X, $box.Y, $box.Side, $box.Side)),
        [System.Drawing.GraphicsUnit]::Pixel)
    $gr.Dispose()
    $dir = Split-Path $outPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output ("  {0,-62} {1}px" -f $outPath, $canvas)
}

# =============================================================================
# 1. ΑΝΟΙΧΤΗ ΠΑΡΑΛΛΑΓΗ  (πάνω-αριστερά)
# =============================================================================
$light = Get-Quadrant 0 0
$lightBox = Get-ContentBox $light $Tolerance
Write-Output "Ανοιχτή: σχήμα $($lightBox.W)x$($lightBox.H) -> τετράγωνο $($lightBox.Side)px στο ($($lightBox.X),$($lightBox.Y))"

# Adaptive icon foreground: 108dp canvas ανά density. Μόνο το κεντρικό ~66%
# φαίνεται σίγουρα (το σύστημα κόβει σε κύκλο/squircle) — κρατάμε 62%.
$fg = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }
foreach ($d in $fg.Keys) {
    Write-Scaled $light $lightBox $fg[$d] 0.62 (Join-Path $ResDir "mipmap-$d/ic_launcher_foreground.png")
}

$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($d in $legacy.Keys) {
    Write-Scaled $light $lightBox $legacy[$d] 0.92 (Join-Path $ResDir "mipmap-$d/ic_launcher.png")
    Write-Scaled $light $lightBox $legacy[$d] 0.92 (Join-Path $ResDir "mipmap-$d/ic_launcher_round.png")
}

# Λογότυπο μέσα στην εφαρμογή (splash, κεφαλίδα μενού) — ανοιχτό θέμα.
Write-Scaled $light $lightBox 512 0.96 (Join-Path $ResDir "drawable-nodpi/logo.png")

# Λογότυπο 120x120 για την οθόνη συγκατάθεσης OAuth της Google.
Write-Scaled $light $lightBox 120 0.94 "docs/oauth-logo.png"

# =============================================================================
# 2. ΣΚΟΤΕΙΝΗ ΠΑΡΑΛΛΑΓΗ  (κάτω-αριστερά)
# =============================================================================
# Χωριστό αρχείο αντί για tint: η σκούρα παραλλαγή δεν είναι αντιστροφή
# χρωμάτων — έχει λάμψη γύρω από τον φάκελο και διαφορετική απόχρωση στα
# έντυπα. Το qualifier `-night` το διαλέγει μόνο του το σύστημα.
$dark = Get-Quadrant 0 1
$darkBox = Get-ContentBox $dark $DarkTolerance
Write-Output "Σκοτεινή: σχήμα $($darkBox.W)x$($darkBox.H) -> τετράγωνο $($darkBox.Side)px στο ($($darkBox.X),$($darkBox.Y))"
Write-Scaled $dark $darkBox 512 0.96 (Join-Path $ResDir "drawable-night-nodpi/logo.png")

$light.Dispose(); $dark.Dispose(); $src.Dispose()
Write-Output "Έτοιμο."
