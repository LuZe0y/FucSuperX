#!/usr/bin/env bash
# 无 Gradle 构建：javac -> d8 -> aapt2 -> 注入 META-INF/xposed -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-35.0.0}
AJAR=$SDK/platforms/android-35/android.jar
API=libs/libxposed-api-102.jar
# 模块界面进程用的 LSPosed 服务库（远程偏好，无需 root），打进 dex
SVC=libs/libxposed-service-102.jar:libs/libxposed-interface-102.jar
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex

echo '[1/6] javac'
# stubs/：隐藏 API 的编译期桩，只放进 classpath，不进 dex（运行时用 framework 里的真类）
mkdir -p $OUT/stubs
javac -nowarn -Xlint:-options -source 17 -target 17 -encoding UTF-8 \
  -cp "$AJAR" -d $OUT/stubs $(find stubs -name '*.java')
javac -nowarn -Xlint:-options -source 17 -target 17 -encoding UTF-8 \
  -cp "$OUT/stubs:$AJAR:$API:$SVC" -d $OUT/classes $(find src -name '*.java')

echo '[2/6] d8'
$BT/d8 --release --min-api 31 --lib "$AJAR" --classpath "$API" \
  --output $OUT/dex $(find $OUT/classes -name '*.class') ${SVC//:/ }

echo '[3/6] aapt2'
$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -I "$AJAR" --manifest AndroidManifest.xml -o $OUT/base.apk $OUT/res.zip

echo '[4/6] pack dex + META-INF/xposed'
python3 - "$OUT" <<'PY'
import sys, zipfile, shutil
out = sys.argv[1]
shutil.copy(f"{out}/base.apk", f"{out}/unsigned.apk")
with zipfile.ZipFile(f"{out}/unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{out}/dex/classes.dex", "classes.dex")
    for n in ("module.prop", "java_init.list", "scope.list"):
        z.write(f"xposed/{n}", f"META-INF/xposed/{n}")
PY

echo '[5/6] zipalign'
$BT/zipalign -p -f 4 $OUT/unsigned.apk $OUT/aligned.apk

echo '[6/6] apksigner'
# 签名：默认用仓库根目录的 keystore.jks（不存在时自动生成）；CI 通过环境变量传入正式密钥
KS=${KEYSTORE_PATH:-keystore.jks}
KS_PASS=${KEYSTORE_PASSWORD:-android}
KEY_ALIAS=${KEY_ALIAS:-land}
KEY_PASS=${KEY_PASSWORD:-$KS_PASS}
if [ ! -f "$KS" ]; then
  echo "  $KS 不存在，生成新的签名密钥"
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 36500 -dname 'CN=FucSuperX' >/dev/null 2>&1
fi
$BT/apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
  --out $OUT/FucSuperX.apk $OUT/aligned.apk
$BT/apksigner verify $OUT/FucSuperX.apk
unzip -l $OUT/FucSuperX.apk | grep -E 'classes.dex|META-INF/xposed'
echo "OK -> $(pwd)/$OUT/FucSuperX.apk"
