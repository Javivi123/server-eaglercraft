$java = "C:\Users\javsr\Downloads\Eaglercraft_Server\jre\bin\java.exe"
$ecj = "C:\Users\javsr\.gemini\antigravity\brain\029cb84c-86eb-4b50-a7e0-a172b7ad5899\scratch\ecj.jar"
$cp = "C:\Users\javsr\Downloads\Eaglercraft_Server\server\cache\patched_1.8.8.jar"
$srcDir = "C:\Users\javsr\.gemini\antigravity\brain\029cb84c-86eb-4b50-a7e0-a172b7ad5899\scratch\plugin_src"
$outDir = "C:\Users\javsr\.gemini\antigravity\brain\029cb84c-86eb-4b50-a7e0-a172b7ad5899\scratch\plugin_out"
$scratchJar = "C:\Users\javsr\.gemini\antigravity\brain\029cb84c-86eb-4b50-a7e0-a172b7ad5899\scratch\XaviAdminProtect.jar"
$targetJar = "C:\Users\javsr\Downloads\Eaglercraft_Server\server\plugins\XaviAdminProtect.jar"

if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Path $outDir | Out-Null

$javaFiles = Get-ChildItem "$srcDir\com\xavi\protect\*.java" | ForEach-Object { $_.FullName }
& $java -jar $ecj -8 -cp $cp -d $outDir $javaFiles
Copy-Item "$srcDir\plugin.yml" "$outDir\plugin.yml"
Copy-Item "$srcDir\config.yml" "$outDir\config.yml"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (Test-Path $scratchJar) { Remove-Item $scratchJar -Force }
$zip = [System.IO.Compression.ZipFile]::Open($scratchJar, [System.IO.Compression.ZipArchiveMode]::Create)
Get-ChildItem -Path $outDir -Recurse | Where-Object { -not $_.PSIsContainer } | ForEach-Object {
    $rel = $_.FullName.Substring($outDir.Length + 1).Replace('\', '/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel)
}
$zip.Dispose()

Write-Host "Verificando entradas del JAR:"
[System.IO.Compression.ZipFile]::OpenRead($scratchJar).Entries | Select-Object FullName

Copy-Item $scratchJar $targetJar -Force
Write-Host "Copiado a $targetJar con exito!"
