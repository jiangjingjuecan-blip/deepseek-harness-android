# Run by Android's system shell from the signed APK. PRoot handles QUIT by
# reaping its tracees. Its TERM handler ignores ordinary process.destroy().
# FD 3 reaches EOF even if Android kills the Java process without onDestroy.
exec 3<&0
"$@" </dev/null 3<&- &
runtime=$!
(
    IFS= read -r ignored <&3
    while kill -0 "$runtime" 2>/dev/null; do
        kill -QUIT "$runtime" 2>/dev/null
        /system/bin/sleep 1
    done
) &
watcher=$!
wait "$runtime"
code=$?
kill "$watcher" 2>/dev/null
wait "$watcher" 2>/dev/null
exit "$code"
