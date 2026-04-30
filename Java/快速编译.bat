@echo off
setlocal enabledelayedexpansion

echo ========================================
echo   EosMesh Server - Build Script
echo ========================================

:: 清理旧文件
if exist out rmdir /s /q out
if exist lib rmdir /s /q lib
if exist eosmesh.jar del /f /q eosmesh.jar

:: 创建目录
mkdir lib
mkdir out

echo [1/4] Downloading dependencies...
cd lib

:: Jackson (三个核心包)
curl -L -o jackson-databind-2.15.2.jar https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.15.2/jackson-databind-2.15.2.jar
curl -L -o jackson-core-2.15.2.jar https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.15.2/jackson-core-2.15.2.jar
curl -L -o jackson-annotations-2.15.2.jar https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.15.2/jackson-annotations-2.15.2.jar

:: jBCrypt
curl -L -o jbcrypt-0.4.jar https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar

cd ..

echo [2/4] Compiling Java source...
:: 注意：源文件路径需正确，这里假设 EosMeshServer.java 在当前目录
javac -encoding UTF-8 -cp "lib\*" -d out EosMeshServer.java

if errorlevel 1 (
    echo Compilation failed!
    pause
    exit /b 1
)

echo [3/4] Creating manifest...
(
echo Manifest-Version: 1.0
echo Main-Class: EosMeshServer
echo Class-Path: lib/jackson-databind-2.15.2.jar lib/jackson-core-2.15.2.jar lib/jackson-annotations-2.15.2.jar lib/jbcrypt-0.4.jar
) > manifest.txt

echo [4/4] Packaging JAR...
jar cfm eosmesh.jar manifest.txt -C out .

if errorlevel 1 (
    echo Packaging failed!
    pause
    exit /b 1
)

echo.
echo ========================================
echo   Build successful!
echo   Output: eosmesh.jar
echo ========================================
echo.
echo To run: java -jar eosmesh.jar
pause