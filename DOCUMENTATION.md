# ABook — Документация проекта

## Общее описание

ABook — Android-приложение для озвучки электронных книг через встроенный синтезатор речи Google (Text-to-Speech). Приоритет проекта — максимально гибкая настройка голоса. Приложение работает как фоновый сервис (аналогично музыкальному плееру) с управлением через уведомление, lock screen и Bluetooth.

**Стек:** Kotlin, Jetpack Compose, Material 3, Hilt, Room, Coroutines + Flow, MediaSessionCompat
**Min SDK:** 26 (Android 8.0)
**Target/Compile SDK:** 35
**Пакет:** `com.abook`

---

## Структура проекта

```
abook/
├── app/
│   ├── build.gradle.kts              # Зависимости и конфигурация модуля
│   ├── proguard-rules.pro            # Правила ProGuard для release-сборки
│   └── src/main/
│       ├── AndroidManifest.xml       # Разрешения, Activity, Service, Receiver
│       ├── java/com/abook/
│       │   ├── ABookApplication.kt   # @HiltAndroidApp, каналы уведомлений
│       │   ├── MainActivity.kt       # Single-activity хост для Compose
│       │   ├── data/                 # Слой данных
│       │   ├── domain/               # Доменные модели
│       │   ├── service/              # Сервисы TTS и таймера
│       │   ├── di/                   # Hilt DI модули
│       │   ├── ui/                   # Compose UI
│       │   └── util/                 # Утилиты
│       └── res/                      # Ресурсы Android
├── build.gradle.kts                  # Root-проект (плагины)
├── settings.gradle.kts               # Конфигурация репозиториев
├── gradle/
│   ├── libs.versions.toml            # Каталог версий зависимостей
│   └── wrapper/                      # Gradle wrapper
├── gradle.properties                 # JVM и Android настройки
├── gradlew / gradlew.bat             # Скрипты запуска Gradle
└── local.properties                  # Путь к Android SDK (не коммитить)
```

---

## Архитектура

Приложение построено по паттерну **MVVM** с чётким разделением слоёв:

```
UI (Compose Screens + ViewModels)
        ↕ StateFlow / Events
Service Layer (TtsPlaybackService ↔ TtsEngine, AudioEffectsManager, SleepTimerManager)
        ↕ Room DAO
Data Layer (Room Database, Entities)
```

### Связь Activity ↔ Service

ViewModels привязываются к `TtsPlaybackService` через `bindService()`. Сервис предоставляет `StateFlow<PlaybackState>` и `StateFlow<SleepTimerState>`, которые ViewModels собирают и транслируют в UI.

---

## Слой данных (`data/`)

### База данных — Room

**Файл:** `data/db/ABookDatabase.kt`
**Имя БД:** `abook.db`
**Версия:** 1

#### Таблицы (Entity)

| Файл | Таблица | Описание |
|---|---|---|
| `entity/BookEntity.kt` | `books` | Метаданные книг: id, title, author, filePath, format, coverPath, totalChapters, addedAt, lastOpenedAt |
| `entity/ChapterEntity.kt` | `chapters` | Главы: id, bookId (FK → books), index, title, textContent, charOffset. Каскадное удаление при удалении книги |
| `entity/ReadingPositionEntity.kt` | `reading_positions` | Позиция чтения: bookId (PK), chapterIndex, charOffsetInChapter, updatedAt |
| `entity/VoiceProfileEntity.kt` | `voice_profiles` | Профили голоса: все TTS-параметры + эквалайзер + эффекты + SSML |
| `entity/SleepScheduleEntity.kt` | `sleep_schedules` | Расписание таймера: час, минута, длительность, дни недели, fadeout |

#### DAO

| Файл | Описание |
|---|---|
| `dao/BookDao.kt` | CRUD книг, глав и позиций чтения. `getAllBooks()` возвращает `Flow<List<BookEntity>>` отсортированный по lastOpenedAt |
| `dao/VoiceProfileDao.kt` | CRUD профилей голоса. `getAllProfiles()` возвращает Flow, дефолтный профиль первым |
| `dao/SleepScheduleDao.kt` | Получение/обновление расписания сна |

### Ключевые решения

- **Текст глав хранится в Room** как TEXT-столбец. SQLite справляется с многомегабайтными значениями.
- **Позиция отслеживается по charOffset** (смещение в символах от начала главы), а не по страницам — это точнее и не зависит от формата.
- **Эквалайзер сохраняется** как `equalizerBandLevels: String` (через запятую) и `equalizerPreset: Int`.

---

## Доменные модели (`domain/model/`)

| Файл | Описание |
|---|---|
| `Book.kt` | `Book` (domain) + `Chapter` (domain) + `BookFormat` enum (FB2, EPUB, TXT, PDF) |
| `VoiceProfile.kt` | Доменная модель профиля голоса со всеми параметрами TTS и аудиоэффектов |
| `PlaybackState.kt` | `PlaybackState` — текущее состояние воспроизведения (isPlaying, книга, глава, прогрессы). `SleepTimerState` — состояние таймера (isActive, remainingSeconds, isFadingOut, displayTime) |

---

## Сервисный слой (`service/`) — ЯДРО ПРИЛОЖЕНИЯ

### TtsEngine.kt — Обёртка над Android TTS

**Ответственность:** Инициализация TextToSpeech, управление голосом, чанкинг текста, обратные вызовы прогресса.

#### Возможности:

| Метод | Что делает |
|---|---|
| `initialize(onReady)` | Инициализирует TTS, создаёт audio session ID, регистрирует UtteranceProgressListener |
| `getAvailableVoices()` | Перечисляет все голоса → `List<VoiceInfo>` с name, locale, quality, latency, requiresNetwork, features |
| `getAvailableLocales()` | Список доступных языков/локалей |
| `setVoice(voiceName)` | Устанавливает конкретный голос по имени |
| `setLanguage(locale)` | Устанавливает язык |
| `setSpeechRate(rate)` | 0.1–4.0, по умолчанию 1.0 |
| `setPitch(pitch)` | 0.1–2.0, по умолчанию 1.0 |
| `setVolume(volume)` | 0.0–1.0, передаётся через `KEY_PARAM_VOLUME` в Bundle |
| `setPan(pan)` | -1.0–1.0, стерео-панорама через `KEY_PARAM_PAN` |
| `setSsmlEnabled(enabled)` | Включает SSML-обёртку текста |
| `setSsmlPauseMs(ms)` | 0–5000мс пауза между предложениями |
| `speak(text, utteranceId, queueMode)` | Озвучивает текст. Если SSML включён — оборачивает через SsmlBuilder |
| `chunkText(text, maxChunkSize)` | Разбивает текст на куски по ~2000 символов по границам предложений → `List<TextChunk>` |

#### Callbacks:
- `onUtteranceStart` / `onUtteranceDone` / `onUtteranceError` — начало/конец/ошибка фрагмента
- `onRangeStart(utteranceId, start, end)` — прогресс на уровне слов (API 26+)

#### Формат utteranceId:
```
bookId:chapterIndex:charOffset
```
Например: `abc123:5:12400` — книга abc123, глава 5, начало с символа 12400.

#### Audio Session ID:
Генерируется через `AudioManager.generateAudioSessionId()` и передаётся в TTS через `KEY_PARAM_SESSION_ID`. К этому же session ID подключаются аудиоэффекты.

---

### AudioEffectsManager.kt — Эквалайзер и аудиоэффекты

**Ответственность:** Управление цепочкой аудиоэффектов, привязанных к audio session ID TTS.

| Метод | Что делает |
|---|---|
| `initialize(sessionId)` | Создаёт Equalizer, BassBoost, Virtualizer для данной сессии |
| `getEqualizerInfo()` | Возвращает `EqualizerInfo`: кол-во полос, min/max уровень, частоты, пресеты, текущие уровни |
| `setEqualizerPreset(preset)` | Применяет пресет (Normal, Classical, Dance, Flat, Folk, Heavy Metal, Hip Hop, Jazz, Pop, Rock) |
| `setEqualizerBandLevel(band, level)` | Ручная настройка уровня конкретной частотной полосы (в миллибелах) |
| `setBassBoostStrength(strength)` | 0–1000, усиление низких частот |
| `setVirtualizerStrength(strength)` | 0–1000, пространственный звук |
| `setEnabled(enabled)` | Включает/отключает все эффекты |
| `release()` | Освобождает ресурсы |

**Важно:** Не все OEM-производители корректно поддерживают audio effects через session ID. При ошибках — эффекты пропускаются (catch + Log.w).

---

### SleepTimerManager.kt — Таймер засыпания

**Ответственность:** Обратный отсчёт, плавное затухание, детекция встряхивания.

#### Режимы:
- **Ручной запуск:** `start(durationMinutes, currentVolume)` — запускает countdown
- **Расширение:** `extend(minutes)` — +15 мин (или другое кол-во), отменяет fadeout если был
- **Отмена:** `cancel()` — останавливает таймер, восстанавливает громкость
- **Shake-to-extend:** Акселерометр, порог 15 м/с², cooldown 2 секунды

#### Fadeout:
Последние 120 секунд (`FADE_DURATION_SECONDS`) — каждую секунду громкость снижается пропорционально:
```
volume = originalVolume * (remainingSeconds / 120.0)
```
Через `onVolumeChange` callback передаётся в TtsEngine.

#### State:
`StateFlow<SleepTimerState>` — isActive, remainingSeconds, isFadingOut, displayTime ("5:30").

---

### SleepTimerAlarmReceiver.kt — BroadcastReceiver

Принимает Intent от AlarmManager для авторасписания таймера. Передаёт duration и fadeOut в TtsPlaybackService через `ACTION_START_SLEEP_TIMER`.

---

### TtsPlaybackService.kt — Foreground Service (главный компонент)

**Ответственность:** Оркестрация всего: TTS, MediaSession, уведомление, audio focus, сохранение позиции.

#### Жизненный цикл:
1. `onCreate()` — инициализация TtsEngine, AudioEffectsManager, SleepTimerManager, MediaSession
2. `onBind()` — возвращает `LocalBinder` для привязки из ViewModel
3. `onStartCommand()` — обработка action-интентов (play, pause, stop, next, prev, sleep timer)
4. `onDestroy()` — сохранение позиции, освобождение всех ресурсов

#### Управление воспроизведением:

| Метод | Описание |
|---|---|
| `playBook(bookId, chapterIndex, charOffset)` | Загружает книгу из Room, запускает foreground service, начинает озвучку |
| `pause()` | Останавливает TTS, сохраняет позицию, отпускает audio focus |
| `resume()` | Продолжает с сохранённой позиции |
| `nextChapter()` / `prevChapter()` | Переход между главами |
| `seekToChapter(index)` | Прыжок к произвольной главе |
| `seekByCharOffset(delta)` | Смещение на N символов вперёд/назад |

#### Чанкинг и воспроизведение:
1. Текст главы разбивается на chunks по ~2000 символов (`TtsEngine.chunkText()`)
2. Каждый chunk озвучивается через `tts.speak(QUEUE_ADD)` с utteranceId
3. `onRangeStart()` обновляет позицию на уровне слов
4. `onUtteranceDone()` при завершении последнего chunk-а переходит к следующей главе

#### Audio Focus:
- `AUDIOFOCUS_GAIN` — запрашивается при play
- `AUDIOFOCUS_LOSS` → pause
- `AUDIOFOCUS_LOSS_TRANSIENT` → pause
- `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` → громкость 30%
- `AUDIOFOCUS_GAIN` (возврат) → громкость 100%

#### MediaSession:
- `MediaSessionCompat` с callback-ами: onPlay, onPause, onStop, onSkipToNext, onSkipToPrevious
- Metadata: bookTitle → ALBUM, chapterTitle → TITLE, номер главы → TRACK_NUMBER
- PlaybackState: PLAYING/PAUSED, position = charOffsetInChapter

#### Уведомление:
- Канал: `playback` (IMPORTANCE_LOW, без звука)
- 4 action-кнопки: Назад, Play/Пауза, Вперёд, Стоп
- MediaStyle с compact view (3 кнопки)
- Ongoing = true при воспроизведении

#### Интенты:
```
ACTION_PLAY    = "com.abook.action.PLAY"
ACTION_PAUSE   = "com.abook.action.PAUSE"
ACTION_STOP    = "com.abook.action.STOP"
ACTION_NEXT_CHAPTER = "com.abook.action.NEXT_CHAPTER"
ACTION_PREV_CHAPTER = "com.abook.action.PREV_CHAPTER"
ACTION_START_SLEEP_TIMER = "com.abook.action.START_SLEEP_TIMER"
```

---

### SsmlBuilder.kt — Генератор SSML

- `wrapWithPauses(text, pauseMs)` — разбивает текст на предложения, вставляет `<break time="Xms"/>` между ними, оборачивает в `<speak>`
- `splitIntoSentences(text)` — разделяет по `.!?…` + пробел + заглавная буква (русская или латинская)
- XML-экранирование спецсимволов (&, <, >, ", ')

---

## UI слой (`ui/`)

### Навигация (`ui/navigation/`)

**Screen.kt** — sealed class маршрутов:
- `Library` → `"library"`
- `Player` → `"player/{bookId}"`
- `VoiceSettings` → `"voice_settings"`
- `ChapterList` → `"chapters/{bookId}"`

**ABookNavHost.kt** — Compose NavHost + нижняя навигация (3 вкладки):
1. Библиотека (иконка LibraryBooks)
2. Плеер (иконка PlayCircle)
3. Голос (иконка RecordVoiceOver)

Bottom bar скрывается на экранах, не входящих в основные маршруты.

---

### Экран библиотеки (`ui/library/`)

**LibraryScreen.kt:**
- FAB "+" → открывает системный file picker (`ACTION_OPEN_DOCUMENT`)
- Поддерживаемые MIME: fb2, epub, txt, pdf, octet-stream
- `takePersistableUriPermission` для долгосрочного доступа к файлу
- Карточки книг: иконка, название, автор, формат (бейдж), кол-во глав, дата
- Кнопка удаления на каждой карточке
- Пустое состояние: иконка + текст "Библиотека пуста"

**LibraryViewModel.kt:**
- `importBook(uri, contentResolver)` — читает файл, разбивает на главы, сохраняет в Room
- `splitIntoChapters(text)` — ищет заголовки `Глава/Chapter/ГЛАВА/CHAPTER + число`. Fallback: куски по 5000 символов
- `deleteBook(bookId)` — каскадное удаление (Room FK)

---

### Экран плеера (`ui/player/`)

**PlayerScreen.kt:**
- Заголовок: название книги + кнопки "Главы" и "Голос"
- Информация о главе: название, номер/всего
- Два прогресс-бара: глава (primary) и книга (tertiary) с процентами
- Управление: SkipPrevious, Replay (−500 символов), Play/Pause (72dp кнопка), Forward (+500 символов), SkipNext
- Таймер сна: кнопка Bedtime. Одно нажатие → 30 мин. Повторное → BottomSheet с пресетами (15, 30, 45, 60, 90 мин), +15 мин, отключить
- Отображение оставшегося времени (красный при fadeout)

**PlayerViewModel.kt:**
- `bindService()` при init, `unbindService()` при onCleared
- Подписка на `service.playbackState` и `service.sleepTimerState`
- `playBook(bookId)` — загружает позицию из Room, вызывает `service.playBook()`
- `togglePlayPause()`, `nextChapter()`, `prevChapter()`, `seekByCharOffset(delta)`
- `startSleepTimer(minutes)`, `extendSleepTimer()`, `cancelSleepTimer()`

---

### Экран настроек голоса (`ui/voicesettings/`) — TOP PRIORITY FEATURE

**VoiceSettingsScreen.kt** — прокручиваемый экран с секциями:

1. **Профили голоса** — горизонтальный ряд карточек. Активный выделен цветом. Кнопка "Сохранить" → диалог с вводом имени. Удаление (кроме дефолтного).

2. **Основные параметры** — 4 слайдера:
   - Скорость речи: 0.1x–4.0x
   - Высота тона: 0.1–2.0
   - Громкость: 0–100%
   - Панорама L/R: с отображением "L 50%", "Центр", "R 30%"

3. **Выбор голоса** — кнопка → DropdownMenu со всеми доступными голосами. Каждый голос показывает: имя, локаль, quality, latency, требование сети.

4. **Эквалайзер** — пресеты (Dropdown) + ручные слайдеры по каждой частотной полосе (Гц/кГц). Диапазон в миллибелах, отображение в дБ.

5. **Усиление басов** — слайдер 0–100%.

6. **Виртуализатор** — слайдер 0–100%.

7. **SSML паузы** — Switch вкл/выкл + слайдер длительности паузы 0–5000мс.

8. **Предпрослушка** — кнопка внизу + кнопка в AppBar. Озвучивает тестовую фразу на русском.

**VoiceSettingsViewModel.kt:**
- Привязка к сервису для прямого доступа к TtsEngine и AudioEffectsManager
- `loadCurrentState()` — читает текущие параметры из TTS
- Методы для каждого параметра: `setSpeechRate()`, `setPitch()`, `setVolume()`, `setPan()`, `selectVoice()`, `selectLocale()`, `setEqualizerPreset()`, `setEqualizerBandLevel()`, `setBassBoost()`, `setVirtualizer()`, `setSsmlEnabled()`, `setSsmlPauseMs()`
- `previewVoice()` — QUEUE_FLUSH, тестовая фраза
- `saveProfile(name)` — сохраняет все текущие параметры в Room
- `loadProfile(profile)` — восстанавливает все параметры из профиля
- `deleteProfile(profile)` — удаление

---

### Экран глав (`ui/chapters/`)

**ChapterListScreen.kt:**
- LazyColumn со всеми главами книги
- Номер + название. Текущая глава выделена primary-цветом и bold
- Нажатие → возврат на плеер (popBackStack)

**ChapterListViewModel.kt:**
- Загрузка глав из Room по bookId (из SavedStateHandle)
- Загрузка текущей позиции для подсветки

---

### Тема (`ui/theme/`)

- **Color.kt** — Purple80/40, PurpleGrey80/40, Pink80/40
- **Theme.kt** — Material 3. Dynamic color на Android 12+ (Material You). Dark/Light автоматически по системе
- **Type.kt** — headlineLarge (28sp Bold), titleLarge (22sp SemiBold), bodyLarge (16sp), labelMedium (12sp Medium)

---

## DI — Hilt (`di/`)

**AppModule.kt** (`@InstallIn(SingletonComponent)`)
- `provideDatabase()` — Room.databaseBuilder → singleton
- `provideBookDao()`, `provideVoiceProfileDao()`, `provideSleepScheduleDao()` — из Database

---

## Разрешения (AndroidManifest.xml)

| Разрешение | Зачем |
|---|---|
| `FOREGROUND_SERVICE` | Фоновое воспроизведение |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Тип foreground service |
| `POST_NOTIFICATIONS` | Уведомление плеера (API 33+) |
| `SCHEDULE_EXACT_ALARM` | Авторасписание таймера сна |
| `WAKE_LOCK` | Предотвращение засыпания при воспроизведении |

---

## Сборка

### Требования:
- JDK 17 (установлен в `C:\Users\Q\jdk-17`)
- Android SDK 35 (установлен в `C:\Users\Q\Android\Sdk`)
- Gradle 8.9 (скачивается автоматически через wrapper)

### Команда:
```bash
export JAVA_HOME="/c/Users/Q/jdk-17"
export ANDROID_SDK_ROOT="/c/Users/Q/Android/Sdk"
./gradlew assembleDebug --no-daemon
```

### Результат:
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Копия в корне: `abook-debug.apk`

---

## Зависимости (gradle/libs.versions.toml)

| Библиотека | Версия | Назначение |
|---|---|---|
| Compose BOM | 2024.12.01 | UI-фреймворк |
| Material 3 | (через BOM) | Тема, компоненты |
| Material Icons Extended | (через BOM) | Расширенный набор иконок |
| Navigation Compose | 2.8.5 | Навигация между экранами |
| Lifecycle ViewModel | 2.8.7 | ViewModel + Compose интеграция |
| Hilt | 2.53.1 | Dependency Injection |
| Hilt Navigation Compose | 1.2.0 | `hiltViewModel()` в Compose |
| Room | 2.6.1 | ORM для SQLite |
| Media | 1.7.0 | MediaSessionCompat |
| Coroutines | 1.9.0 | Асинхронность |
| DataStore | 1.1.1 | Хранение настроек |
| Jsoup | 1.18.3 | Парсинг HTML (для EPUB) |
| iTextPDF | 8.0.5 | Извлечение текста из PDF |
| KSP | 2.1.0-1.0.29 | Генерация кода (Room, Hilt) |
