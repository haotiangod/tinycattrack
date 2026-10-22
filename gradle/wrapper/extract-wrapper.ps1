# 提取 Gradle Wrapper 所需的所有文件
$zipPath = "c:\Users\Administrator\Desktop\sts-stat-tracker-master\gradle\wrapper\gradle-8.0.zip"
$outputDir = "c:\Users\Administrator\Desktop\sts-stat-tracker-master\gradle\wrapper"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)

# 查找所有 wrapper 相关的文件
Write-Host "查找 wrapper 相关文件..."
$wrapperFiles = $zip.Entries | Where-Object { 
    $_.FullName -like "gradle-8.0/lib/gradle-wrapper*" -or 
    $_.FullName -like "gradle-8.0/lib/plugins/gradle-wrapper*"
}

foreach ($file in $wrapperFiles) {
    Write-Host "找到：$($file.FullName)"
}

# 提取 gradle-wrapper-8.0.jar
$wrapperJar = $wrapperFiles | Where-Object { $_.FullName -eq "gradle-8.0/lib/plugins/gradle-wrapper-8.0.jar" }
if ($wrapperJar) {
    $outputPath = Join-Path $outputDir "gradle-wrapper.jar"
    $stream = $wrapperJar.Open()
    $fileStream = [System.IO.File]::Create($outputPath)
    $stream.CopyTo($fileStream)
    $fileStream.Close()
    $stream.Close()
    Write-Host "成功提取 gradle-wrapper.jar 到 $outputPath"
} else {
    Write-Host "未找到 gradle-wrapper-8.0.jar 文件"
}

$zip.Dispose()
