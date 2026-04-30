#!/bin/bash

# 切换到脚本所在目录
cd "$(dirname "$0")" || exit 1

echo "========================================"
echo "  EosMesh Server - Build Script (Bash)"
echo "========================================"

# 清理旧文件
rm -rf out lib eosmesh.jar manifest.txt

# 创建目录
mkdir -p lib out

echo "[1/4] Downloading dependencies..."
cd lib || exit 1

# Jackson 核心包
curl -L -o jackson-databind-2.15.2.jar https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.15.2/jackson-databind-2.15.2.jar
curl -L -o jackson-core-2.15.2.jar https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.15.2/jackson-core-2.15.2.jar
curl -L -o jackson-annotations-2.15.2.jar https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.15.2/jackson-annotations-2.15.2.jar

# jBCrypt
curl -L -o jbcrypt-0.4.jar https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar

cd ..

echo "[2/4] Compiling Java source..."
javac -encoding UTF-8 -cp "lib/*" -d out EosMeshServer.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "[3/4] Creating manifest..."
cat > manifest.txt << EOF
Manifest-Version: 1.0
Main-Class: EosMeshServer
Class-Path: lib/jackson-databind-2.15.2.jar lib/jackson-core-2.15.2.jar lib/jackson-annotations-2.15.2.jar lib/jbcrypt-0.4.jar
EOF

echo "[4/4] Packaging JAR..."
jar cfm eosmesh.jar manifest.txt -C out .

if [ $? -ne 0 ]; then
    echo "Packaging failed!"
    exit 1
fi

echo ""
echo "========================================"
echo "  Build successful!"
echo "  Output: eosmesh.jar"
echo "========================================"
echo ""
echo "To run: java -jar eosmesh.jar"