#!/system/bin/sh
# 查看 FucSuperX 在 LSPosed 远程偏好里的配置值（可选参数：键名过滤，默认全部）
cp /data/adb/lspd/config/modules_config.db* /data/local/tmp/ 2>/dev/null
cd /data/local/tmp
echo "== schema module_configs"
sqlite3 modules_config.db ".schema module_configs"
echo "== FucSuperX configs"
sqlite3 -header modules_config.db "select * from module_configs where module_pkg_name like '%islandglass%' and \"key\" like '%${1:-}%';"
rm -f /data/local/tmp/modules_config.db*
