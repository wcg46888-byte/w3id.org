#!/usr/bin/env bash
# 不依赖 Android Studio / Gradle 的 APK 打包脚本。
# 依赖（Ubuntu/Debian）: apt-get install aapt zipalign apksigner dalvik-exchange
# 以及 JDK 8+。缺少的 jar（android.jar / Kotlin 编译器 / stdlib）会自动下载。
#
# 用法: ./build-apk.sh
# 产物: build/AutoClicker-debug.apk
set -euo pipefail
cd "$(dirname "$0")"

TOOLS_DIR="${TOOLS_DIR:-$PWD/.build-tools}"
BUILD_DIR="$PWD/build"
SRC_DIR="app/src/main"
KOTLIN_VER=1.9.24

mkdir -p "$TOOLS_DIR" "$BUILD_DIR"/{gen,classes,stdlib-classes,apk}

fetch() { # fetch <文件名> <URL>
  [ -s "$TOOLS_DIR/$1" ] || { echo "下载 $1 ..."; curl -sfL -o "$TOOLS_DIR/$1" "$2"; }
}

fetch android-34.jar "https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar"
fetch kotlin-compiler-embeddable.jar "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VER/kotlin-compiler-embeddable-$KOTLIN_VER.jar"
fetch kotlin-stdlib.jar "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VER/kotlin-stdlib-$KOTLIN_VER.jar"
fetch annotations.jar "https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar"
fetch trove4j.jar "https://repo.maven.apache.org/maven2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"

ANDROID_JAR="$TOOLS_DIR/android-34.jar"

echo "==> 1/7 生成构建用 AndroidManifest（注入 package 与 uses-sdk）"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.autoclicker" android:versionCode="1" android:versionName="1.0">\n    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34" />|' \
  "$SRC_DIR/AndroidManifest.xml" > "$BUILD_DIR/AndroidManifest.xml"

echo "==> 2/7 aapt 编译资源并生成 R.java"
aapt package -f -m \
  -J "$BUILD_DIR/gen" \
  -M "$BUILD_DIR/AndroidManifest.xml" \
  -S "$SRC_DIR/res" \
  -I "$ANDROID_JAR"

echo "==> 3/7 编译 R.java (javac)"
javac --release 8 -d "$BUILD_DIR/classes" "$BUILD_DIR/gen/com/example/autoclicker/R.java" 2>/dev/null

echo "==> 4/7 编译 Kotlin 源码"
java -cp "$TOOLS_DIR/kotlin-compiler-embeddable.jar:$TOOLS_DIR/kotlin-stdlib.jar:$TOOLS_DIR/annotations.jar:$TOOLS_DIR/trove4j.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class \
  -classpath "$ANDROID_JAR:$BUILD_DIR/classes:$TOOLS_DIR/kotlin-stdlib.jar:$TOOLS_DIR/annotations.jar" \
  -d "$BUILD_DIR/classes" \
  "$SRC_DIR/java"

echo "==> 5/7 dx 转换为 classes.dex（含 kotlin-stdlib）"
rm -rf "$BUILD_DIR/stdlib-classes"; mkdir -p "$BUILD_DIR/stdlib-classes"
unzip -qo "$TOOLS_DIR/kotlin-stdlib.jar" -d "$BUILD_DIR/stdlib-classes" -x "META-INF/*" || true
# dx 不支持 invokedynamic：剔除 stdlib 中少数使用它的类（Java Stream / Comparator 辅助，本应用未使用）
find "$BUILD_DIR/stdlib-classes" -name '*.class' \
  -exec grep -l --binary-files=text "LambdaMetafactory" {} + | xargs -r rm -v
rm -f "$BUILD_DIR/stdlib-classes/module-info.class"
dalvik-exchange --dex --min-sdk-version=24 \
  --output="$BUILD_DIR/apk/classes.dex" \
  "$BUILD_DIR/classes" "$BUILD_DIR/stdlib-classes"

echo "==> 6/7 打包 APK 并对齐"
aapt package -f \
  -M "$BUILD_DIR/AndroidManifest.xml" \
  -S "$SRC_DIR/res" \
  -I "$ANDROID_JAR" \
  -F "$BUILD_DIR/app.unaligned.apk"
(cd "$BUILD_DIR/apk" && aapt add -f "$BUILD_DIR/app.unaligned.apk" classes.dex)
zipalign -f 4 "$BUILD_DIR/app.unaligned.apk" "$BUILD_DIR/app.aligned.apk"

echo "==> 7/7 签名（debug 证书）"
if [ ! -f "$TOOLS_DIR/debug.keystore" ]; then
  keytool -genkeypair -keystore "$TOOLS_DIR/debug.keystore" \
    -alias androiddebugkey -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi
apksigner sign \
  --ks "$TOOLS_DIR/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD_DIR/AutoClicker-debug.apk" \
  "$BUILD_DIR/app.aligned.apk"
apksigner verify --print-certs "$BUILD_DIR/AutoClicker-debug.apk" | head -3

echo
echo "✅ 完成: $BUILD_DIR/AutoClicker-debug.apk"
du -h "$BUILD_DIR/AutoClicker-debug.apk"
