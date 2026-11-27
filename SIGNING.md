# Подпись release APK

Идентификатор приложения: `com.mok.buylist`. Для обновления на Android необходимо использовать тот же идентификатор и сертификат подписи, а versionCode не должен уменьшаться. При каждом новом релизе увеличивайте versionCode в app/build.gradle.kts. Сейчас 2 (1.1).

## Постоянный ключ

Создайте один PKCS12 keystore через Android Studio (Build → Generate Signed App Bundle / APK → Create new) или JDK keytool. Храните файл вне репозитория и сделайте защищённую резервную копию. Не генерируйте новый ключ для каждой сборки. Если надо обновить уже установленный APK без переустановки, используйте исходный keystore с тем же сертификатом. При переходе с другого ключа сначала экспортируйте данные, удалите старое приложение и восстановите данные после установки.

## Локальная сборка

В личном файле `~/.gradle/gradle.properties` (в Windows `%USERPROFILE%\.gradle\gradle.properties`) укажите:

```properties
BUYLIST_STORE_FILE=C:/private/buylist-release.p12
BUYLIST_STORE_PASSWORD=YOUR_PASSWORD
BUYLIST_KEY_ALIAS=buylist
BUYLIST_KEY_PASSWORD=YOUR_PASSWORD
```

Для PKCS12, созданного keytool, пароль ключа обычно совпадает с паролем хранилища. Вместо файла можно задать одноимённые переменные окружения. Не храните пароли в проектном gradle.properties.

```powershell
.\gradlew.bat :app:assembleRelease
```

## GitHub Actions

В Settings → Secrets and variables → Actions → Repository secrets добавьте:

- BUYLIST_KEYSTORE_BASE64: содержимое keystore в Base64 (не шифрование, хранить только как secret).
- BUYLIST_STORE_PASSWORD: пароль хранилища.
- BUYLIST_KEY_ALIAS: alias ключа.
- BUYLIST_KEY_PASSWORD: пароль ключа.

Workflow FRESH CUT APK проверяет наличие секретов, восстанавливает keystore во временном каталоге runner, удаляет его после сборки и проверяет APK через apksigner. При отсутствии секретов release не собирается с чужим debug-ключом.

Сохраните публичный SHA-256 отпечаток сертификата для сверки будущих APK. Не публикуйте keystore и пароли.
