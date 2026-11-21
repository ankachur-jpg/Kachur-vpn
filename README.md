# Kachur VPN

Персональный VPN-клиент для Android.

## Основные функции
- Подключение к собственному VPN-серверу
- Раздельная маршрутизация трафика
- Банки, Госуслуги и маркетплейсы без VPN
- Инвестиционные приложения без VPN
- Импорт JSON со списками IP-адресов
- Автоматическое обновление правил
- Ручное управление исключениями
- Диагностика соединения

## Статус
Проект находится на начальной стадии разработки.
На главном экране можно импортировать локальный профиль AmneziaWG из файла `.conf`. Импортированный профиль хранится в памяти до закрытия приложения. Для подключения требуется интеграция совместимого движка AmneziaWG 2.0.

### Движок Android

Официальный [AmneziaWG для Android 2.0.0](https://github.com/amnezia-vpn/amneziawg-android/releases/tag/2.0.0) добавил поддержку протокола AWG 2.0. Версия [2.0.1](https://github.com/amnezia-vpn/amneziawg-android/releases/tag/2.0.1) использует [amneziawg-go v0.2.18](https://github.com/amnezia-vpn/amneziawg-android/blob/2.0.1/tunnel/tools/libwg-go/go.mod), а её [GoBackend](https://github.com/amnezia-vpn/amneziawg-android/blob/2.0.1/tunnel/src/main/java/org/amnezia/awg/backend/GoBackend.java) создаёт TUN через Android VpnService.

Подключение официального модуля требует сборки из закреплённой ревизии. Upstream документирует сборку с подмодулями, CMake/NDK и Go. Публикация официального AAR обсуждается в [issue #100](https://github.com/amnezia-vpn/amneziawg-android/issues/100). [Сборка модуля](https://github.com/amnezia-vpn/amneziawg-android/blob/2.0.1/tunnel/build.gradle.kts).

В текущей сборке Kachur VPN доступен импорт, а кнопка подключения сообщает о состоянии VPN. Перед подключением движка нужно исключить вывод данных профиля в Logcat, включая ошибки JNI и Go. Например, нативная обёртка пишет ошибку `IpcSet` целиком, а ошибка разбора endpoint включает значение из профиля. [Android-обёртка](https://github.com/amnezia-vpn/amneziawg-android/blob/2.0.1/tunnel/tools/libwg-go/api-android.go) · [Обработка UAPI](https://github.com/amnezia-vpn/amneziawg-go/blob/v0.2.18/device/uapi.go).

Для интеграции сервису понадобятся системное согласие через `VpnService.prepare()`, переход в foreground с уведомлением, защита транспортных сокетов через `protect()` и освобождение TUN и ресурсов нативного движка при отключении или отзыве разрешения. Преобразование профиля должно сохранять параметры AWG 2.0 и отклонять параметры, которые выбранная версия движка не поддерживает. Создание TUN и подтверждение связи с сервером следует отображать отдельно. Проверка подключения требует физического Android и тестового сервера AWG 2.0 для проверки handshake, трафика, отключения и отзыва разрешения. [Документация Android VPN](https://developer.android.com/develop/connectivity/vpn).
