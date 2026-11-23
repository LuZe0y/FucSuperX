#!/system/bin/sh
P=$(pidof com.android.systemui)
echo "pid=$P"
echo "== maps lsposed/module"
grep -iE 'lspd|lsposed|islandglass|framework\.dex' /proc/$P/maps | awk '{print $6}' | sort -u | head -20
echo "== fd to module apk"
ls -l /proc/$P/fd 2>/dev/null | grep -iE 'islandglass|lspd|lsposed' | head
echo "== logcat pid (xposed/lsposed/IslandGlass)"
logcat -d -b all --pid=$P | grep -iE 'lsposed|xposed|IslandGlass|libxposed' | head -20
echo "== lsposed logcat mentioning module/systemui"
logcat -d -b all | grep -iE 'lsposed|lspd' | grep -iE 'islandglass|systemui|java_init|module.prop|api' | tail -20
