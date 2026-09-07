# Настройка Firebase (необязательно)

Firebase нужен **только** для синхронизации между устройствами. Без него
приложение полностью работоспособно: Room хранит всё локально, Health Connect
читается напрямую, экспорт работает.

В репозитории лежит **заглушка** `app/google-services.json` с
`project_id = fitness-summary-placeholder`. Она существует потому, что плагин
Google Services роняет сборку, если файла нет вообще. Приложение узнаёт эту
заглушку (`sync/FirebaseSetup.kt`) и честно пишет во вкладке «Я», что облако не
настроено, вместо того чтобы показывать кнопку входа, которая всё равно не сработает.

## 1. Создать проект

1. https://console.firebase.google.com → **Добавить проект**
2. Google Analytics можно отключить — не используется

## 2. Добавить Android-приложение

- Package name: **`com.fitnessapp.summary`** (должен совпадать точно)
- SHA-1 отладочного ключа — обязателен для Google Sign-In.

SHA-1 берётся из закоммиченного debug-keystore (он фиксированный именно ради
стабильности этого отпечатка):

```bash
keytool -list -v -keystore keystore/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

## 3. Заменить google-services.json

Скачать файл из консоли и положить вместо заглушки в `app/google-services.json`.
Закоммитить — он не секрет (это публичные идентификаторы клиента).

## 4. Включить вход

Firebase Console → **Authentication** → **Sign-in method** → включить **Google**.

После этого скопировать **Web client ID** (Authentication → Sign-in method →
Google → Web SDK configuration) и подставить в `app/build.gradle.kts`:

```kotlin
val GOOGLE_WEB_CLIENT_ID_DEFAULT = "ВАШ-ID.apps.googleusercontent.com"
```

Это тоже не секрет — публичный OAuth-идентификатор, поэтому он лежит прямо в
сборочном файле, а не в CI-секрете.

## 5. Создать базу Firestore

Firebase Console → **Firestore Database** → **Создать базу** → режим
**production** (правила всё равно приедут из репозитория).

Структура, которую пишет приложение:

```
users/{uid}/dailySummaries/{epochDay}
users/{uid}/workouts/{healthConnectRecordId}
```

Идентификаторы документов — естественные ключи, поэтому повторная синхронизация
идемпотентна, а один и тот же день с двух телефонов попадает в один документ.

## 6. Автодеплой правил из CI (необязательно)

`firestore.rules` умеет выкатываться сам при пуше в `main`. Для этого нужно
задать в репозитории:

- секрет `FIREBASE_SERVICE_ACCOUNT` — JSON сервисного аккаунта целиком
  (Firebase Console → Настройки проекта → Сервисные аккаунты → Создать ключ)
- переменную `FIREBASE_PROJECT_ID` (Settings → Secrets and variables → Actions →
  вкладка **Variables**) — id проекта

Если чего-то из двух нет, шаг просто пропускается, сборка не падает.

## Что именно уезжает в облако

Дневные сводки (шаги, калории, дистанция, пульс, сон) и тренировки. Это
медицинские по своей сути данные, поэтому правила в `firestore.rules` разрешают
доступ строго к своему `uid` и ничего не открывают наружу.
