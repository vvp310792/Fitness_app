#!/usr/bin/env bash
# Прогон SmokeScreensTest на эмуляторе CI: ставит оба APK, запускает тест и забирает
# снимки экранов, лог приложения и logcat в out/ — их выкладывает шаг upload-artifact.
# Вызывается из reactivecircus/android-emulator-runner, эмулятор к этому моменту уже загружен.
set -u

OUT=out
mkdir -p "$OUT"

APP_APK=app/build/outputs/apk/debug/fitness-summary-debug.apk
# Тестовый APK правило applicationVariants не переименовывает (это не вариант приложения),
# поэтому ищем по маске, а не по угаданному имени.
TEST_APK=$(ls app/build/outputs/apk/androidTest/debug/*.apk | head -n 1)

adb install -r -g "$APP_APK"
adb install -r -g "$TEST_APK"

adb logcat -c || true

# am instrument возвращает 0 и при упавшем тесте — итог читается по тексту вывода.
adb shell am instrument -w -r \
  com.fitnessapp.summary.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$OUT/instrument.txt"

adb pull /sdcard/Android/data/com.fitnessapp.summary/files/shots "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt" || true
# Падения приложения — отдельным файлом, чтобы не искать их в многомегабайтном logcat.
adb logcat -d -b crash > "$OUT/crash.txt" || true

if grep -q "FAILURES!!!" "$OUT/instrument.txt" || ! grep -q "^OK (" "$OUT/instrument.txt"; then
  echo "Тест на эмуляторе не прошёл — см. out/instrument.txt и out/crash.txt" >&2
  exit 1
fi
echo "Тест на эмуляторе прошёл"
