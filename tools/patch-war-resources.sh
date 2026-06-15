#!/bin/sh
# Uso: patch-war-resources.sh path/openproteo.war path/src/main/resources
WAR="$1"; RES="$2"
[ -z "$WAR" ] || [ -z "$RES" ] && { echo "Uso: $0 openproteo.war src/main/resources"; exit 1; }
rm -rf _warstage; mkdir -p _warstage/WEB-INF/classes
cp -r "$RES"/* _warstage/WEB-INF/classes/
( cd _warstage && jar uf "$WAR" -C . . ) 2>/dev/null || ( cd _warstage && jar uf "../$WAR" . )
rm -rf _warstage
echo "Patched $WAR"; jar tf "$WAR" | grep templates/dashboard.html
