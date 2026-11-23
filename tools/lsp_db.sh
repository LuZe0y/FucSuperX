#!/system/bin/sh
cp /data/adb/lspd/config/modules_config.db* /data/local/tmp/ 2>/dev/null
cd /data/local/tmp
for t in modules scope modules_state; do echo "== schema $t"; sqlite3 modules_config.db ".schema $t"; done
echo "== modules (islandglass)"
sqlite3 -header modules_config.db "select * from modules where module_pkg_name like '%islandglass%';"
echo "== modules_state"
sqlite3 -header modules_config.db "select * from modules_state;" | head -20
echo "== scope rows for islandglass"
sqlite3 -header modules_config.db "select * from scope where module_pkg_name like '%islandglass%';"
echo "== scope rows (others, for comparison)"
sqlite3 -header modules_config.db "select * from scope limit 8;"
rm -f /data/local/tmp/modules_config.db*
