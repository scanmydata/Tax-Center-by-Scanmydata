# tools/make-icons.ps1 — παράγει τα εικονίδια της εφαρμογής από το λογότυπο.
# =============================================================================
# Το λογότυπο είναι ένα φύλλο 2x2 με τέσσερις παραλλαγές (ανοιχτό/σκούρο φόντο).
# Κρατάμε το ΠΑΝΩ ΑΡΙΣΤΕΡΑ (ανοιχτό φόντο): το adaptive icon βάζει δικό του
# λευκό background, οπότε η σκούρα παραλλαγή δεν χρειάζεται.
#
# Χρήση:
#   powershell -ExecutionPolicy Bypass -File tools/make-icons.ps1 `
#       -Source "C:\...\λογότυπο.png"
#
# Γιατί PowerShell και όχι Node: το System.Drawing υπάρχει ήδη στα Windows, ενώ
# η αποκωδικοποίηση PNG στο Node θα ήθελε native dependency (sharp) για μια
# δουλειά που γίνεται μία φορά. Το Prosfora-APK κάνει το ίδιο με Python/Pillow.

param(
    [Parameter(Mandatory = $true)][string]$Source,
    [string]$ResDir = "app/src/main/res",
    # Κάτω από αυτή τη φωτεινότητα ένα pixel μετράει ως «σχήμα». Το λογότυπο
    # κάθεται σε ένα σχεδόν-λευκό πάνελ με αχνό περίγραμμα (~240): με 244 το
    # περίγραμμα μετρούσε ως περιεχόμενο και το bbox έβγαινε όλο το τεταρτημόριο.
    [int]$Threshold = 225
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { throw "Δεν βρέθηκε το λογότυπο: $Source" }

$src = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Source))
Write-Output "Πηγή: $($src.Width)x$($src.Height)"

# --- 1. Πάνω-αριστερά τεταρτημόριο -------------------------------------------
$qw = [int]($src.Width / 2)
$qh = [int]($src.Height / 2)
$quad = New-Object System.Drawing.Bitmap($qw, $qh)
$g = [System.Drawing.Graphics]::FromImage($quad)
$g.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $qw, $qh)),
             (New-Object System.Drawing.Rectangle(0, 0, $qw, $qh)),
             [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()

# --- 2. Bounding box του σχήματος (ό,τι δεν είναι σχεδόν-λευκό) ---------------
# Το δείγμα γίνεται ανά 2 pixel: αρκετά ακριβές, πολύ πιο γρήγορο.
$minX = $qw; $minY = $qh; $maxX = 0; $maxY = 0
$threshold = $Threshold
for ($y = 0; $y -lt $qh; $y += 2) {
    for ($x = 0; $x -lt $qw; $x += 2) {
        $p = $quad.GetPixel($x, $y)
        if ($p.A -gt 24 -and ($p.R -lt $threshold -or $p.G -lt $threshold -or $p.B -lt $threshold)) {
            if ($x -lt $minX) { $minX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
if ($maxX -le $minX -or $maxY -le $minY) { throw "Δεν βρέθηκε σχήμα στο τεταρτημόριο." }

# Τετράγωνο bbox, κεντραρισμένο — το εικονίδιο δεν πρέπει να παραμορφωθεί.
$bw = $maxX - $minX + 1
$bh = $maxY - $minY + 1
$side = [Math]::Max($bw, $bh)
$cx = $minX + $bw / 2
$cy = $minY + $bh / 2
$sx = [int][Math]::Max(0, $cx - $side / 2)
$sy = [int][Math]::Max(0, $cy - $side / 2)
$side = [int][Math]::Min($side, [Math]::Min($qw - $sx, $qh - $sy))
Write-Output "Σχήμα: ${bw}x${bh} -> τετράγωνο ${side}px στο ($sx,$sy)"

# --- 3. Παραγωγή ---------------------------------------------------------------
# Στο adaptive icon μόνο το κεντρικό ~66% φαίνεται σίγουρα (το σύστημα κόβει σε
# κύκλο/squircle). Κρατάμε 62% για ασφάλεια, όπως κάνει και το Prosfora.
function Write-Scaled([int]$canvas, [double]$fraction, [string]$outPath) {
    $bmp = New-Object System.Drawing.Bitmap($canvas, $canvas)
    $gr = [System.Drawing.Graphics]::FromImage($bmp)
    $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gr.Clear([System.Drawing.Color]::Transparent)
    $target = [int]($canvas * $fraction)
    $off = [int](($canvas - $target) / 2)
    $gr.DrawImage($quad, (New-Object System.Drawing.Rectangle($off, $off, $target, $target)),
                  (New-Object System.Drawing.Rectangle($sx, $sy, $side, $side)),
                  [System.Drawing.GraphicsUnit]::Pixel)
    $gr.Dispose()
    $dir = Split-Path $outPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "  $outPath  (${canvas}px)"
}

# Adaptive icon foreground: 108dp canvas ανά density.
$densities = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }
foreach ($d in $densities.Keys) {
    Write-Scaled $densities[$d] 0.62 (Join-Path $ResDir "mipmap-$d/ic_launcher_foreground.png")
}

# Legacy launcher icons (API < 26 δεν υπάρχει εδώ — minSdk 26 — αλλά κάποια
# launchers και το Play Console ζητούν ακόμη το ic_launcher bitmap).
$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($d in $legacy.Keys) {
    Write-Scaled $legacy[$d] 0.92 (Join-Path $ResDir "mipmap-$d/ic_launcher.png")
    Write-Scaled $legacy[$d] 0.92 (Join-Path $ResDir "mipmap-$d/ic_launcher_round.png")
}

# Λογότυπο 120x120 για την οθόνη συγκατάθεσης OAuth της Google.
Write-Scaled 120 0.94 "docs/oauth-logo.png"

$quad.Dispose()
$src.Dispose()
Write-Output "Έτοιμο."
