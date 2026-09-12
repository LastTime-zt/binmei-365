# 构建注册码管理器 AdminManager.apk (纯 aapt2+javac+d8)
$ErrorActionPreference = "Stop"
$src = $PSScriptRoot
$root = "C:\aab_build_admin"
Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $root | Out-Null
Copy-Item "$src\AdminApp\AndroidManifest.xml" $root -Force
Copy-Item "$src\AdminApp\java" "$root\java" -Recurse -Force
Copy-Item "$src\AdminApp\res" "$root\res" -Recurse -Force
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$bt   = "$sdk\build-tools\35.0.0"
$plat = "$sdk\platforms\android-34\android.jar"

# 0. 编译资源
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$root\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 1. 链接
& "$bt\aapt2.exe" link -o "$root\unsigned.apk" -I $plat --manifest "$root\AndroidManifest.xml" "$root\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 2. javac
New-Item -ItemType Directory -Force "$root\classes" | Out-Null
$srcs = @(Get-ChildItem "$root\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 21 -target 21 -classpath $plat -d "$root\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 3. d8
New-Item -ItemType Directory -Force "$root\dexout" | Out-Null
$cls = Get-ChildItem "$root\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 24 --lib $plat --output "$root\dexout" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# 4. dex 入 apk
Copy-Item "$root\unsigned.apk" "$root\withdex.apk" -Force
python -c "import zipfile; z=zipfile.ZipFile(r'$root\withdex.apk','a'); z.write(r'$root\dexout\classes.dex','classes.dex'); z.close()"

# 5. zipalign + 签名(与主APP同一 keystore, 简化管理)
& "$bt\zipalign.exe" -f 4 "$root\withdex.apk" "$root\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
$ks = "$src\AutoAnswerApp\debug.keystore"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$root\AdminManager.apk" "$root\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "BUILD OK -> $root\AdminManager.apk"
Copy-Item "$root\AdminManager.apk" "$src\AdminApp\AdminManager.apk" -Force

if ($args -notcontains "-noinstall") {
    adb install -r "$root\AdminManager.apk"
}
