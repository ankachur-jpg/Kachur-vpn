# Персональный релиз Kachur VPN

Версия 1.0.0 использует `versionCode 10000` и пакет `com.kachur.vpn`. Для обновления Android проверяет имя пакета, сертификат подписи и номер версии. При следующем выпуске используйте тот же сертификат, оставьте имя пакета прежним и увеличьте `versionCode` в `app/build.gradle.kts`. Например, для 1.0.1 подойдёт 10001. [Версии приложений Android](https://developer.android.com/studio/publish/versioning) · [Подпись приложений Android](https://developer.android.com/studio/publish/app-signing)

## Инструменты на Fedora

Сборка нативного движка рассчитана на Fedora x86-64. Установите Git, unzip и Python через `sudo dnf install git unzip python3 make curl`. Используйте JDK 17, Gradle 8.10.2 и Go 1.25.0. Добавьте `gradle` и `go` в `PATH`, а `JAVA_HOME` укажите на установленный JDK 17. В выпуске Fedora, где пакет JDK 17 доступен, его можно установить как `java-17-openjdk-devel`. Другой вариант установки JDK 17 — Temurin. [Fedora Packages](https://packages.fedoraproject.org/pkgs/java-17-openjdk/java-17-openjdk-devel/) · [Temurin 17](https://adoptium.net/temurin/releases/?version=17) · [Gradle](https://gradle.org/releases/) · [Go](https://go.dev/dl/)

Установите Android SDK Command-line Tools и укажите корень SDK. При другом расположении инструментов используйте свой путь.

~~~bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "platforms;android-36" \
  "build-tools;34.0.0" "build-tools;36.0.0" "ndk;28.2.13676358" "cmake;3.22.1"
~~~

[Android SDK Command-line Tools](https://developer.android.com/studio#command-tools)

Из корня репозитория соберите закреплённый движок AmneziaWG. При повторной подготовке движка используйте новый временный checkout, поскольку скрипт применяет локальный патч.

~~~bash
awg_checkout=$(mktemp -d)
git -C "$awg_checkout" init
git -C "$awg_checkout" fetch --depth 1 https://github.com/amnezia-vpn/amneziawg-android.git \
  5c16489e2cd9ed3a0a7a27c7445bba5238132f86
git -C "$awg_checkout" checkout --detach FETCH_HEAD
git -C "$awg_checkout" submodule update --init --recursive
bash third_party/amneziawg/build.sh "$awg_checkout"
~~~

[Сборка и лицензии AmneziaWG](../third_party/amneziawg/README.md)

## Создание личного ключа

Выполните команды один раз на своём доверенном компьютере. Укажите длинный уникальный пароль в запросе `keytool` и сохраните его в менеджере паролей. Хранилище создаётся вне checkout, в каталоге с доступом только для владельца.

~~~bash
umask 077
kachur_keys="$HOME/.local/share/kachur-vpn-signing"
install -d -m 700 "$kachur_keys"

keytool -genkeypair -storetype PKCS12 \
  -keystore "$kachur_keys/kachur-vpn.p12" -alias kachur-vpn \
  -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 \
  -dname "CN=Kachur VPN Personal Release"
chmod 600 "$kachur_keys/kachur-vpn.p12"

keytool -exportcert -storetype PKCS12 \
  -keystore "$kachur_keys/kachur-vpn.p12" -alias kachur-vpn | sha256sum
~~~

Сохраните первый столбец вывода `sha256sum` как ожидаемый отпечаток сертификата. Он публичный. При последующих сборках используйте исходный сохранённый отпечаток и то же хранилище. Вводите пароли в запросах терминала. Храните хранилище и пароль вне GitHub, облачных сборок и общих папок. [Управление ключом подписи Android](https://developer.android.com/studio/publish/app-signing#secure-key)

## Сборка и проверка

Запустите скрипт после подготовки движка и подставьте сохранённый 64-символьный отпечаток.

~~~bash
bash scripts/build-personal-release.sh \
  "$kachur_keys/kachur-vpn.p12" kachur-vpn 'СОХРАНЁННЫЙ_SHA256'
~~~

Gradle собирает неподписанный release APK. Скрипт выравнивает APK, подписывает его выбранным ключом после запроса пароля, проверяет подпись и сравнивает сертификат с ожидаемым. Проверенный файл появляется в `dist/Kachur-VPN-release.apk`. Четвёртым аргументом можно указать другой выходной путь.

~~~bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  dist/Kachur-VPN-release.apk
~~~

Сверьте строку `Signer #1 certificate SHA-256 digest` с сохранённым отпечатком. [Документация apksigner](https://developer.android.com/tools/apksigner)

## Переход с debug на release

Android отклоняет установку release поверх debug с другим сертификатом. Перенесите данные через зашифрованную копию до удаления debug.

1. Отключите VPN. Откройте «Настройки» → «Резервные копии» → «Создать зашифрованную копию». Задайте отдельный пароль копии и сохраните `.kvb` в «Загрузках», «Документах» или на внешнем носителе, за пределами каталога приложения. Скопируйте файл в отдельное безопасное место.
2. В debug откройте сохранённую копию, введите пароль и дождитесь предварительного просмотра. Проверьте число профилей и правил и закройте просмотр. Удаляйте debug только после проверки копии и доступа к паролю.
3. Удалите debug в системных настройках и установите подписанный release APK. Те же действия можно выполнить с подключённого по ADB компьютера после проверки копии.

   ~~~bash
   adb devices
   adb uninstall com.kachur.vpn
   adb install dist/Kachur-VPN-release.apk
   ~~~

4. В release откройте «Резервные копии», выберите тот же `.kvb`, введите пароль и подтвердите восстановление. Проверьте выбранный сервер, профили и исключения. Оформление и показ обычного уведомления можно настроить в приложении, а разрешения Android и плитку быстрых настроек — заново на устройстве.

Для следующего **release → release** сохраните резервную копию и установите APK с более высоким `versionCode` и прежним сертификатом командой `adb install -r dist/Kachur-VPN-release.apk`. Android сохраняет каталог данных и ключ Android Keystore при таком обновлении.

## Резервная копия ключа подписи

Храните `.p12` на двух независимых зашифрованных носителях. Пароль храните отдельно. Запишите рядом с архивом псевдоним `kachur-vpn`, публичный SHA-256 сертификата и последний выпущенный `versionCode`. После копирования проверьте открытие каждой копии через `keytool -exportcert` и сверку отпечатка. Храните пароль от `.kvb` отдельно от пароля ключа подписи.

При потере ключа APK с новым сертификатом потребует чистой установки. Сохранённый `.kvb` и его пароль позволяют перенести профили и настройки в эту установку.

## Проверка в CI

CI собирает неподписанный release. Тест миграции использует короткоживущий сертификат в одноразовом каталоге runner. На эмуляторе он экспортирует синтетическую зашифрованную копию, проверяет отказ установки поверх debug, восстанавливает копию после переустановки и обновляет release на следующий `versionCode` с тем же тестовым сертификатом. В артефакты сборки попадает debug APK. Тестовый ключ, копия и подписанные тестовые APK удаляются после прогона.
