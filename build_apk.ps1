# 自动构建 AutoAnswer.apk 并安装（纯 aapt2+javac+d8，无需 gradle）
$ErrorActionPreference = "Stop"
$src = $PSScriptRoot
# aapt2 不认中文路径, 复制到纯英文临时目录构建
$root = "C:\aab_build"
Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $root | Out-Null
Copy-Item "$src\AndroidManifest.xml", "$src\res", "$src\java", "$src\assets" $root -Recurse -Force
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$bt   = "$sdk\build-tools\35.0.0"
$plat = "$sdk\platforms\android-34\android.jar"

# 0. 题库进 assets（优先从本地复制，不存在则跳过）
New-Item -ItemType Directory -Force "$root\assets" | Out-Null
$bankSrc = if (Test-Path "d:\Code\android-逆向\彬煤安培365\question_bank.db") { "d:\Code\android-逆向\彬煤安培365\question_bank.db" }
            elseif (Test-Path "$src\AutoAnswerApp\assets\question_bank.db") { "$src\AutoAnswerApp\assets\question_bank.db" }
            else { $null }
if ($bankSrc) {
    Copy-Item $bankSrc "$root\assets\question_bank.db" -Force
}

# 1. 编译资源
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$root\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 2. 链接（含 assets、生成 R.java）
$linkArgs = @('link', '-o', "$root\unsigned.apk", '-I', $plat,
    '--manifest', "$root\AndroidManifest.xml",
    '-A', "$root\assets",
    '--java', "$root\gen", '--auto-add-overlay', "$root\res.zip")
& "$bt\aapt2.exe" @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 3. javac 编译
New-Item -ItemType Directory -Force "$root\classes" | Out-Null
Remove-Item "$root\classes\*" -Recurse -Force -ErrorAction SilentlyContinue
$srcs = @(Get-ChildItem "$root\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += Get-ChildItem "$root\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 21 -target 21 -classpath $plat -d "$root\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 4. d8 转 dex
New-Item -ItemType Directory -Force "$root\dexout" | Out-Null
Remove-Item "$root\dexout\*" -Force -ErrorAction SilentlyContinue
$cls = Get-ChildItem "$root\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 24 --lib $plat --output "$root\dexout" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# 5. dex 打进 apk
Copy-Item "$root\unsigned.apk" "$root\withdex.apk" -Force
python -c "import zipfile; z=zipfile.ZipFile(r'$root\withdex.apk','a'); z.write(r'$root\dexout\classes.dex','classes.dex'); z.close()"

# 6. zipalign
& "$bt\zipalign.exe" -f 4 "$root\withdex.apk" "$root\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# 7. 签名（keystore 持久化, 保证签名一致）
$ks = "$src\debug.keystore"
if (!(Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias debug -storepass android `
        -keypass android -dname "CN=AutoAnswer,O=Local,C=CN" -keyalg RSA -keysize 2048 -validity 10000
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$root\AutoAnswer.apk" "$root\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "BUILD OK -> $root\AutoAnswer.apk"
Copy-Item "$root\AutoAnswer.apk" "$src\AutoAnswer.apk" -Force

# 8. 安装（可选参数 -noinstall 跳过）
if ($args -notcontains "-noinstall") {
    adb install -r "$root\AutoAnswer.apk"
}
