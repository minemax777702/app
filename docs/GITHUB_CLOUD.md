# GitHub Cloud — этапы 4–6, регистрация, восстановление и ручные копии (Beta)

Встроенный пользовательский экран: Профиль → Облачное хранилище. Административный пункт по умолчанию скрыт. Семь быстрых нажатий на версию сборки внизу профиля открывают его и сохраняют видимость между запусками. Действие «Скрыть административный раздел» завершает сессию и убирает пункт. Жест не заменяет проверку PAT или системную аутентификацию. Сохранённый токен не удаляется при скрытии. Существующие локальные профиль, Room, Bluetooth/QR и резервные копии не изменены. Покупки и каталоги не синхронизируются автоматически; администратор может явно загрузить зашифрованную копию всей локальной базы. Администратор может явно создать зашифрованные предварительные записи пользователей.

## Настройка администратора

1. Создать приватный app-cloud в аккаунте minemax777702 либо использовать кнопку создания в приложении.
2. Для уже существующего репозитория создать fine-grained PAT, ограниченный app-cloud, с Administration: Read and write; Contents: Read and write; Metadata: Read (обязательное). Для создания нового репозитория может потребоваться более широкий доступ; предпочтительно создать его в браузере и использовать узко ограниченный токен.
3. Ввести токен в приложении, проверить аккаунт и приватность репозитория. Токен не передаётся в пользовательские данные и не хранится в исходном коде. После успешной проверки владельца можно сохранить вход: AES-256-GCM, ключ Android Keystore требует отдельной сильной биометрии или системного PIN/пароля/графического ключа для каждой операции. Шифротекст находится в noBackupFilesDir; ключ и токен не экспортируются. На Android 10 или без блокировки экрана доступен ручной вход. При разблокировке заново проверяются токен и владелец через GitHub. При утрате ключа или отзыве токена удалите сохранённый вход и введите новый. Экран защищён FLAG_SECURE, сессия очищается при выходе или уходе приложения в фон.
4. Отключить Actions в репозитории данных. Приложение не выдаёт ключи устройств на этом этапе.

REST API: https://docs.github.com/en/rest/repos/repos ; https://docs.github.com/en/rest/users/users ; https://docs.github.com/en/rest/actions/permissions

## Предварительный реестр пользователей

В административном разделе после отключения Actions: Пользователи → Добавить пользователя. Запись имеет UUID, имя и статус архива; не является аккаунтом, не даёт прав и не связана с локальным профилем. Файл system/admin-users.v1.enc хранится в приватном app-cloud в AES-GCM. Запись использует SHA предыдущей версии, при конфликте требуется перечитывание. Ключ не экспортируется из Android Keystore. До реализации восстановления доступ возможен только на создавшей реестр установке, удаление приложения может привести к потере ключа. Не использовать для реальных персональных данных, пока не проверены восстановление и подпись манифестов. При исчезновении файла или откате версия проверяется локальным checkpoint. Архивирование обратимо; удаление и отзыв прав устройств пока не реализованы.

Beta выполняет JVM-тесты пакета com.mok.buylist.cloud перед сборкой APK; Stable выполняет весь набор JVM-тестов.

## Группы

Администратор создаёт, переименовывает и архивирует группы, назначает одного владельца, участников и читателей. Передача владения атомарна, прежний владелец становится участником. Архивный пользователь не может состоять в активной группе; для восстановления группы сначала восстановить всех её участников. Роли — черновики, не предоставляют доступ к GitHub или данным покупок. Формат полезной нагрузки BUC2 читает BUC1 без потери записей; путь файла, ключ и AEAD-контекст сохранены. После записи BUC2 предыдущая Beta не сможет читать реестр и должна отказать в записи. До проверки восстановления на реальных устройствах использовать тестовые записи.

## Регистрация устройств

Профиль → Облачное хранилище → Подключить устройство: открытый P-256 ключ Android Keystore, подпись SHA256withECDSA, срок QR 10 минут. Администратор сканирует QR, лично сверяет полный SHA-256 отпечаток, выбирает активного пользователя и регистрирует ключ. Повторный ключ и отзыв с последующей регистрацией запрещены. Это только реестр, не deploy key, SSH не включён. Формат BUC3 читает BUC1 и BUC2; старые версии после записи BUC3 не должны использоваться для редактирования.

## Восстановление административного реестра

Файл .blcloud содержит зашифрованный снимок реестра и SHA текущего файла GitHub, не содержит PAT, приватных ключей устройства или покупок. AES-256-GCM, PBKDF2-HMAC-SHA256 600000 итераций, случайные соль и nonce; парольная фраза от 16 символов. На другом телефоне администратор авторизуется своим PAT, выбирает файл и подтверждает перенос. Восстановление возможно только если SHA облачного файла совпадает; при более свежей версии или исчезновении файла операция запрещена. Реестр переупаковывается ключом Keystore текущего устройства с увеличением ревизии. Старый телефон с другим ключом теряет доступ к актуальному реестру. После каждой правки и переноса необходимо сохранять новую копию. Пароль и копию хранить отдельно. Это не резервное копирование покупок и не восстановление приватных ключей устройства.

## Ручные облачные копии покупок

В административном разделе: Резервные копии покупок. Копия BackupCodec шифруется AES-256-GCM с PBKDF2-HMAC-SHA256 (600000 итераций), паролем от 16 символов и отдельным контекстом аутентификации. Загружается только по явному действию администратора в backups/manual/<uuid>.enc, новые файлы не перезаписывают старые. До 512 КиБ сжатой копии, до 999 записей в выдаче; более крупные копии экспортировать локально. Имена и сведения о владельце находятся внутри шифротекста. При восстановлении проверяется SHA выбранного файла, AEAD, формат BackupCodec; показываются владелец и счётчики, сохраняется точка отмены, применяется существующее транзакционное слияние. Идентификатор профиля меняется только по явному выбору. Восстановление не заменяет базу, не разделяет личные и групповые данные и не является автоматической синхронизацией. Хранить отдельную копию вне GitHub.

## Протокол будущего обмена

Добавлены JVM-модели CloudSpace, подписанного манифеста, ACL с ролями и версии ключа, зашифрованного подписанного delta-события и курсора цепочки на устройство. Верификация привязана к заранее доверенному ключу администратора, ID пространства, epoch и версии манифеста. События читателя, чужой подписи, старого манифеста, другой группы, пропусков последовательности и повторной отправки после продвижения курсора отклоняются; повтор последнего идентичного события идемпотентен. CloudSnapshotDelta выбирает изменившиеся записи и явные tombstones, включая родительские товары для штрихкодов. Эти классы не подключены к сети и Room, добавлен ограниченный бинарный wire-формат BMF1/BEV1 и отдельный постоянный журнал CloudJournalStore. Для смены epoch нужен отдельный проверяемый checkpoint и распределение новых ключей.

## Локальный журнал пространств

CloudJournalStore хранит каждое пространство в отдельном файле noBackupFilesDir/cloud-journals-v1 с отдельным AES-GCM ключом Android Keystore. Снимок, курсоры, доверенный ключ администратора и отпечаток манифеста записываются одной операцией AtomicFile. Данные другого пространства не расшифровываются даже при совпадающем UUID; одинаковая ревизия с другим содержимым прав отклоняется. Повреждённый журнал не заменяется автоматически. Пока это отдельный компонент, не привязанный к экрану списка, Room или сетевому планировщику. Для использования в боевом режиме нужны выдача ключей, безопасная первичная настройка, обработка смены epoch и постоянная исходящая очередь.

## Диагностика SSH

Профиль → Облачное хранилище → Помощь с подключением → Диагностика SSH. Отдельный тестовый P-256 ключ Android Keystore; копируется только открытый ключ в OpenSSH. Администратор вручную добавляет его в Settings → Deploy keys приватного app-cloud, не устанавливая Allow write access. Проверка подключается к ssh.github.com:443 либо github.com:22, проверяет официальный ECDSA host key строго, а затем читает только первую строку объявления Git refs через git-upload-pack. Ключ сервера взят из https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints ; порт 443 из https://docs.github.com/en/authentication/troubleshooting-ssh/using-ssh-over-the-https-port . SSH-библиотека mwiede/jsch 2.28.8. После теста удалить deploy key на GitHub. Не использовать write access. Это диагностика, не доставка событий или активация пользователя.

## Следующие этапы

- Реестр пользователей и групп: подписанные администратором манифесты, личные и групповые пространства, идентификаторы и версии ключей.
- QR-приглашения: отдельная пара ключей устройства, проверка отпечатка при передаче, одноразовое подтверждение, отсутствие административного токена на клиенте.
- Транспорт SSH с проверкой host key и отдельным deploy key на устройство; API GitHub только для административных операций.
- Шифрование персональных и групповых данных с AEAD, подпись событий, проверка полномочий и ротация ключей. Участник с правом записи в единый репозиторий технически может повредить чужие файлы: подпись выявляет подделку, но не обеспечивает доступность; резервные копии вне облака обязательны. Старые доступные данные нельзя отозвать.
- Изолированные контексты Room/экранов и идемпотентное объединение событий; сначала тесты миграций, архивов и конфликтов, затем автоматическая синхронизация.
- Проверить реальную доступность API/SSH из сети пользователя, подпись APK, Android UI и настройку репозитория.

Не считать этапы 4–6 готовой облачной синхронизацией. Транспорт SSH, выдача реальных полномочий, изоляция рабочих пространств в UI/Room и автоматическая синхронизация ещё не включены. Подтверждение регистрации не создаёт Deploy Key. Не добавлять ключи записи в GitHub, пока не проверены подписание манифестов, ротация ключей и изоляция Room. JGit/SSH-библиотека требует отдельной проверки совместимости Android и Android Keystore.

## Интеграционная самопроверка

Профиль → Облачное хранилище → Помощь с подключением → Самопроверка облака. Синтетические пользователи, два независимых экземпляра Room в памяти и отдельные временные профили. Используются реальные кодеки, подписи, слияние и сериализация журнала. Проверяются независимое добавление, порядок событий, повтор после повторного открытия атомарного журнала на диске, изменение количества, удаление, роли, чужое пространство, подпись и архив. Не используется основной ShoppingApp.repository; виджет и файл отката не затрагиваются. Добавлен AndroidJUnit4-тест CloudIntegrationProbeTest; Beta запускает весь набор JVM-тестов и собирает instrumentation APK. Отдельный workflow Cloud integration tests запускает CloudIntegrationProbeTest и существующий SyncRepositoryTest на эмуляторе Android 35 с KVM. На телефоне доступна та же локальная самопроверка. Самопроверка локальная, не доказывает работу GitHub SSH, реальной активации или ротации ключей.

## Git transport and SSH enrollment

A registered public key can now be explicitly added as a read-only Deploy Key from the device registry. The client checks the same Keystore key (not the independent diagnostic key). Revoking registration first removes the managed GitHub key. Provisioning is limited to read-only until workspace key distribution and role enforcement are complete. Existing manually created keys are never silently adopted or overwritten.

CloudGitEvents implements real Git fetch/push of signed event envelopes via JGit 6.10.1.202505221210-r. A per-space/epoch/device branch contains one event per commit; no remote force-push. It checks sequence, previous digest and acknowledged cursor, supports retry of the identical tip event, and rejects divergent history. CloudGitSsh uses the pinned JSch connection and registered device key; no PAT or shell. JVM test uses two local bare repositories plus a bare remote. This is not yet connected to an Android workspace, durable outbox, or real network write. JGit compatibility on actual Android remains to be tested. Never enable write deploy keys until those integration gates are met.

### Durable delivery

CloudOutbox persists exact signed encrypted event bytes before push. CloudSyncEngine verifies the manifest and event before upload; it acknowledges only after CloudJournalStore commits the accepted cursor. If Git confirms a push but the network response is lost, retry uses the same bytes and is a no-op at the identical remote tip. Pull validates event chains through the persisted journal. Android instrumentation test CloudGitDeliveryTest uses local bare repositories and simulates lost acknowledgment. The engine is not scheduled and is not wired to default Room. Real users currently only receive a read-only Deploy Key; content key delivery, live manifest updates and key rotation remain required before enabling writes.

Статус ключей при открытии реестра сверяется с GitHub, неизвестный статус отображается отдельно. Ошибка чтения ключей не заменяет локальный реестр.

## CI and Android compatibility

JGit 7.8.1.202611041320-r includes the upstream Android PID guard (JGit commit 908a6a5848342883575fcdec5e917048de2a53e5). Java compile target is 17; Android API 29+ unchanged. R8 ignores only the desktop ProcessHandle reference, which is guarded upstream. Release builds retain JVM tests. Emulator integration tests are manual (Actions → Cloud integration tests → Run workflow → beta), with explicit emulator serial and boot logs. Publication keeps GITHUB_TOKEN and contents: write, creates/verifies the exact release tag separately and refuses mismatched tags. No extra token is required by the workflow; if GitHub rejects tag creation, the owner may create the exact tag manually and rerun only publish. The signed APK remains an artifact even when publication fails.

## Ошибки проверки административного реестра

REG-01: неизвестный контейнер; REG-02: не прошла аутентификация AES-GCM (не тот ключ или повреждение); REG-03: подлинная полезная нагрузка не декодируется; REG-04: сбой Keystore или криптопровайдера; REG-05: несовпадение размера/хеша Git blob. Проверка формата и защита от отката сохраняются. Новые записи проверяются локальным расшифрованием перед отправкой. Не удалять ключ, приложение или удалённый файл для обхода ошибки. Восстановление из актуального .blcloud возможно только для точного SHA; при отсутствии ключа и копии расшифровка старых записей невозможна.

### SSH diagnostic reports

The test key is unchanged. Compare the OpenSSH SHA256 fingerprint with Deploy keys in app-cloud. The new report tests the local Keystore signature, observes allowlisted SSH milestones (TCP, banner, KEX, host key, public key and authentication), distinguishes errors SSH-01 through SSH-08, and checks Git read access. Run both ports and copy the report. No raw server replies, PAT, private key or shopping data are logged. Strict host key checking remains mandatory. Read-only deploy key is sufficient. R8 retains the configured JSch algorithms and probes only negotiated algorithms.

REG-05 fallback: the Contents API SHA identifies the immutable Git blob. If inline Base64 cannot be verified, retrieve the same blob via raw bytes and then JSON Base64 using the official Git Blobs API. Verify Git SHA before decrypting; the reported size alone is not authoritative when the object hash matches. REG-05A = invalid path/SHA, REG-05B = inline decode failed and fallback failed, REG-05C = SHA mismatch and fallback failed. Error text includes bounded sizes and HTTP status, never token or ciphertext. No write or reset occurs during this recovery attempt.

SSH-01 before any successful stage with IllegalArgumentException → ClassNotFoundException: JSch KnownHosts eagerly loads HMACSHA1 via reflection before authentication; add keep rules for HMACSHA1, SHA1 and KeyPairGenECDSA. No SHA-1 SSH MAC is enabled. CI checks required class descriptors in the signed beta APK with dexdump; diagnostics show missing JSch class names only. Existing keys are unchanged.

### Проверка подключения зарегистрированного устройства

Кнопка «Проверить подключение» использует read-only SSH Git upload-pack напрямую через CloudSshProbe с ключом CloudDeviceIdentity, не тестовым SSH-ключом. Это проверка авторизации и доступа к репозиторию, а не доказательство работоспособности полного JGit-транспорта. Порт 443 основной, 22 дополнительный для диагностики. Ошибки показываются в интерфейсе, отчёт можно скопировать. Не удалять приложение и существующие ключи. Реальная синхронизация и JGit на Android требуют отдельных интеграционных проверок.

## Упрощённое управление

Админка: пользователи, группы, устройства. Подключение QR одним подтверждением, новый пользователь и устройство сохраняются одним изменением реестра, затем выдаётся read-only ключ. При сбое ключа запись остаётся для повторной активации. Удаление требует подтверждения; сначала проверяется структура, затем отзываются соответствующие Deploy Keys, затем сохраняется реестр по SHA. Удаление пользователя исключает его из групп и удаляет группы без других участников; для группы с другими участниками необходимо передать владение. Удаление группы изменяет только административный реестр. Покупки, архивы и файлы резервных копий не удаляются. Удалённое устройство можно зарегистрировать снова только после нового подтверждения администратора. Прежние архивные записи можно восстановить или удалить. Диагностические панели убраны из навигации, детали ошибок доступны по запросу. Beta больше не запускает JVM-тесты автоматически; проверка подписи и классов APK сохранена. Исходники тестов сохранены. Полный обмен покупками и выдача ключей рабочих пространств ещё не завершены.
