#!/bin/bash
first_device=`adb devices | awk  'NR==2' | awk  '{print $1}'`
packageName="com.malin.hook"
echo "apk will install to "$first_device
adb -s $first_device uninstall $packageName
gradle -q installDebug -x lint --no-build-cache --no-daemon --parallel --offline --continue &&
adb -s $first_device shell am start $packageName/.MainActivity
