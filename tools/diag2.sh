#!/system/bin/sh
P=$(pidof com.android.systemui)
echo "pid=$P  current apk: $(pm path io.luze0y.islandglass)"
for p in io.github.re_televip.televip top.hookvip.pro; do
  a=$(pm path $p | head -1 | cut -d: -f2)
  echo "== $p module.prop"; unzip -p $a META-INF/xposed/module.prop; echo
  echo "== $p java_init.list"; unzip -p $a META-INF/xposed/java_init.list; echo
done
echo "== my module.prop"; unzip -p $(pm path io.luze0y.islandglass | cut -d: -f2) META-INF/xposed/module.prop | od -c | head -12
L=$(ls -t /data/adb/lspd/log/verbose_*.log | head -1)
echo "== verbose log: lines for pid $P"
grep -E "[: ]$P:" $L | head -20
echo "== verbose log: LSPosed tags (last 40)"
grep -E "LSPosed|Vector|lspd|libxposed|islandglass" $L | grep -vE "HWUI" | tail -40
