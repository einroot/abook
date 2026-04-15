# ABook — Техническая документация

> Android-приложение для озвучки электронных книг (FB2 / EPUB / PDF / TXT) через системный Text-to-Speech.
> Репозиторий: https://github.com/einroot/abook

**Последнее обновление:** после 6 раундов аудита Play/Pause (~68 коммитов, коммит `c2300c8`)

---

## Оглавление

1. [Обзор](#1-обзор)
2. [Архитектура](#2-архитектура)
3. [Структура проекта](#3-структура-проекта)
4. [Слой данных](#4-слой-данных)
5. [Парсеры форматов](#5-парсеры-форматов)
6. [Доменные модели](#6-доменные-модели)
7. [Сервисный слой — ядро приложения](#7-сервисный-слой)
8. [Жизненный цикл Play / Pause (выстраданные инварианты)](#8-жизненный-цикл-play--pause)
9. [Приоритет MediaSession и маршрутизация гарнитуры](#9-приоритет-mediasession-и-маршрутизация-гарнитуры)
10. [Thread safety](#10-thread-safety)
11. [Пайплайн обработки текста перед TTS](#11-пайплайн-обработки-текста-перед-tts)
12. [UI-слой (Compose)](#12-ui-слой-compose)
13. [Dependency Injection (Hilt)](#13-dependency-injection-hilt)
14. [Разрешения](#14-разрешения)
15. [Сборка и CI](#15-сборка-и-ci)
16. [Зависимости](#16-зависимости)
17. [Диагностика и отладка](#17-диагностика-и-отладка)
18. [История изменений](#18-история-изменений)

---

## 1. Обзор

ABook — аудиокнижный плеер для Android, использующий системный TTS-движок (Google TTS, Samsung TTS и другие установленные) для синтеза речи из книг в форматах FB2, EPUB, PDF, TXT. Ключевой принцип — **максимальная гибкость настроек голоса**: скорость, тон, громкость, панорама, эквалайзер, реверберация, кастомные пресеты, словарь произношений, профили голоса.

### Технологический стек

| Слой | Технологии |
|---|---|
| Язык | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| Асинхронность | Coroutines + StateFlow |
| DI | Hilt (Dagger) |
| Хранилище | Room (SQLite) + DataStore |
| Media | MediaSessionCompat, androidx.media |
| TTS | `android.speech.tts.TextToSpeech` |
| Аудио-эффекты | `android.media.audiofx.*` |
| Парсинг | Jsoup (HTML), iTextPDF (PDF), XmlPullParser (FB2) |

### Целевые платформы

- **Min SDK:** 26 (Android 8.0)
- **Target / Compile SDK:** 35 (Android 15)
- **Пакет:** `com.abook`
- **appCategory:** `audio` (важно для правильной маршрутизации media-кнопок)

---

## 2. Архитектура

Приложение спроектировано по MVVM с ясным разделением на слои:

```
┌─────────────────────────────────────────────────────────────┐
│                        Compose UI                            │
│   Library │ Player │ VoiceSettings │ Bookmarks │ Settings   │
└────────────────────────────┬────────────────────────────────┘
                             │ collectAsState / onClick
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                        ViewModels                            │
│   PlayerVM │ VoiceSettingsVM │ LibraryVM │ BookmarksVM ...  │
└────────────────────────────┬────────────────────────────────┘
                             │ bindService / StateFlow
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                  TtsPlaybackService (Foreground)             │
│  ┌─────────┐ ┌──────────┐ ┌──────────────┐ ┌─────────────┐ │
│  │TtsEngine│ │AudioEffect│ │SleepTimerMgr│ │StatsTracker │ │
│  │         │ │  Manager  │ │              │ │              │ │
│  └─────────┘ └──────────┘ └──────────────┘ └─────────────┘ │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  MediaSessionCompat + Notification (MediaStyle)      │   │
│  │  + Silent AudioTrack Anchor + AudioFocusRequest     │   │
│  └──────────────────────────────────────────────────────┘   │
└────────────────────────────┬────────────────────────────────┘
                             │ suspend / Flow
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                Data layer (Room + DataStore)                 │
│  BookDao │ BookmarkDao │ StatsDao │ VoiceProfileDao │       │
│  SleepScheduleDao                  │ AppPreferences          │
└─────────────────────────────────────────────────────────────┘
```

### Особенности

- **Single-Activity** (`MainActivity`) — единственная точка входа, всё остальное Composable'ы через NavHost.
- **Singleton Service** — `TtsPlaybackService` запускается при первом bind, остаётся в foreground пока пользователь не нажмёт Stop. Управляет всем аудио-конвейером.
- **Binder pattern** — ViewModels биндятся к сервису и получают `StateFlow<PlaybackState>` напрямую.

---

## 3. Структура проекта

```
abook/
├── app/
│   ├── build.gradle.kts                    # Зависимости модуля
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/abook/
│       │   │   ├── ABookApplication.kt     # @HiltAndroidApp
│       │   │   ├── MainActivity.kt         # Single-activity host
│       │   │   ├── data/
│       │   │   │   ├── db/
│       │   │   │   │   ├── ABookDatabase.kt
│       │   │   │   │   ├── dao/            # BookDao, BookmarkDao,
│       │   │   │   │   │                   # StatsDao, VoiceProfileDao,
│       │   │   │   │   │                   # SleepScheduleDao
│       │   │   │   │   └── entity/         # Book, Chapter, ReadingPosition,
│       │   │   │   │                       # Bookmark, ListeningSession,
│       │   │   │   │                       # VoiceProfile, SleepSchedule
│       │   │   │   ├── parser/             # FB2, EPUB, PDF, TXT
│       │   │   │   │   ├── BookParser.kt   # Общий интерфейс
│       │   │   │   │   ├── Fb2Parser.kt
│       │   │   │   │   ├── EpubParser.kt
│       │   │   │   │   ├── PdfParser.kt
│       │   │   │   │   └── TxtParser.kt
│       │   │   │   ├── preferences/
│       │   │   │   │   └── AppPreferences.kt   # DataStore
│       │   │   │   └── repository/
│       │   │   ├── di/                     # AppModule (Hilt)
│       │   │   ├── domain/model/           # PlaybackState, Book, VoiceProfile
│       │   │   ├── service/
│       │   │   │   ├── TtsPlaybackService.kt   # ⭐ Главный оркестратор
│       │   │   │   ├── TtsEngine.kt            # Обёртка над TextToSpeech
│       │   │   │   ├── AudioEffectsManager.kt  # EQ + эффекты
│       │   │   │   ├── SleepTimerManager.kt    # Таймер сна
│       │   │   │   ├── StatsTracker.kt         # Трекинг сессий
│       │   │   │   ├── SsmlBuilder.kt
│       │   │   │   ├── AutoMediaBrowserService.kt   # Android Auto
│       │   │   │   ├── SleepTimerAlarmReceiver.kt
│       │   │   │   ├── BootReceiver.kt
│       │   │   │   └── textprocessing/
│       │   │   │       ├── TextProcessor.kt
│       │   │   │       ├── AbbreviationExpander.kt
│       │   │   │       ├── FootnoteHandler.kt
│       │   │   │       └── PunctuationMapper.kt
│       │   │   ├── sync/
│       │   │   │   └── LibraryExporter.kt
│       │   │   └── ui/
│       │   │       ├── navigation/         # NavHost + Screen + NavViewModel
│       │   │       ├── library/            # Screen + VM
│       │   │       ├── player/             # Screen + VM
│       │   │       ├── voicesettings/      # Screen + VM + VoicePickerDialog
│       │   │       ├── bookmarks/          # Screen + VM
│       │   │       ├── chapters/           # ChapterListScreen + VM
│       │   │       ├── settings/           # Screen + VM
│       │   │       └── theme/              # Color, Theme, Type, ThemeVM
│       │   └── res/                        # Drawables, strings, layouts
│       └── test/
│           ├── java/com/abook/data/parser/
│           │   ├── Fb2ParserTest.kt        # 7 тестов
│           │   └── TxtParserTest.kt
│           └── resources/
│               └── sre-real.fb2            # Регрессионный FB2
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── .github/workflows/ci.yml                # CI: test + build APK
├── build.gradle.kts                        # Root config
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── DOCUMENTATION.md                        # (этот файл)
├── PROPOSALS.md
├── PRIVACY_POLICY.md
└── abook-debug.apk                         # Авто-копия последнего debug APK
```

---

## 4. Слой данных

### Room Database (`data/db/ABookDatabase.kt`)

| Таблица | Entity | Описание |
|---|---|---|
| `books` | `BookEntity` | Метаданные: id, title, author, filePath, format, coverPath, totalChapters, addedAt, lastOpenedAt |
| `chapters` | `ChapterEntity` | Главы: id, bookId (FK), index, title, textContent, charOffset. Каскадное удаление |
| `reading_positions` | `ReadingPositionEntity` | Позиция: bookId (PK), chapterIndex, charOffsetInChapter, updatedAt |
| `bookmarks` | `BookmarkEntity` | Закладки: id, bookId, chapterIndex, charOffset, label, createdAt |
| `voice_profiles` | `VoiceProfileEntity` | Профили голоса: все TTS-параметры + EQ + эффекты + SSML |
| `sleep_schedules` | `SleepScheduleEntity` | Расписание таймера сна |
| `listening_sessions` | `ListeningSessionEntity` | Статистика: startTime, endTime, bookId, charsRead |

### DAO

| DAO | Ключевые методы |
|---|---|
| `BookDao` | `getAllBooks(): Flow<List<BookEntity>>`, `getBook(id)`, `getLastOpenedBookId(): Flow<String?>`, `getLastOpenedBook(): suspend`, `getChapters(bookId)`, `getChapter(bookId, index)`, `savePosition(...)`, `getPosition(bookId)` |
| `BookmarkDao` | `getByBook(bookId): Flow<List<BookmarkEntity>>`, `insert`, `delete` |
| `StatsDao` | `insertSession`, `getSessionsForBook`, `getTotalCharsRead` |
| `VoiceProfileDao` | `getAllProfiles(): Flow<List<VoiceProfileEntity>>`, `insert`, `delete`, дефолтный профиль первым |
| `SleepScheduleDao` | `getSchedule`, `saveSchedule` |

### DataStore (`data/preferences/AppPreferences.kt`)

Настройки, не требующие реляционной модели:

| Ключ | Тип | Назначение |
|---|---|---|
| `theme_mode` | String | `light` / `dark` / `auto` / `amoled` |
| `language` | String | `system` / `ru` / `en` |
| `default_voice_profile_id` | Long | Последний применённый профиль |
| `keep_screen_on` | Boolean | — |
| `auto_play_on_open` | Boolean | — |
| `voice_lang_filter` | String? | Фильтр языка в пикере голоса |
| `seek_short_seconds` | Int | Длина короткой перемотки (default 30с) |
| `seek_long_seconds` | Int | Длина длинной перемотки (default 300с) |

### Ключевые решения

- **Текст глав хранится в Room** как `TEXT`-столбец. SQLite справляется с многомегабайтными значениями.
- **Позиция отслеживается по charOffset** (processed-text space) и конвертируется в original-text space для book-level прогресса.
- **Эквалайзер сохраняется** как `equalizerBandLevels: String` (через запятую) + `equalizerPreset: Int`.
- **Обложки** лежат в `filesDir/covers/{bookId}.jpg`, в БД сохраняется абсолютный путь.

---

## 5. Парсеры форматов

Единый интерфейс `BookParser` (`data/parser/BookParser.kt`):

```kotlin
interface BookParser {
    suspend fun parse(inputStream: InputStream, fileName: String): ParsedBook
}

data class ParsedBook(
    val title: String,
    val author: String,
    val language: String?,
    val description: String?,
    val coverImageBytes: ByteArray?,
    val chapters: List<ParsedChapter>
)

data class ParsedChapter(val title: String, val textContent: String)
```

### Fb2Parser (FictionBook2)

Ключевые тонкости:
- **Детекция кодировки** через `<?xml encoding="..."?>` (Windows-1251, UTF-8, KOI8-R)
- **Метаданные** из `<description>/<title-info>` (book-title, author.first-name/last-name, lang, annotation, coverpage)
- **Обложка** — парсится `<binary id="...">BASE64</binary>` во второй проход
- **Inline-заголовки через пустые `<section>`** — автор может использовать `<section><title>Раздел</title></section>` как встроенный заголовок, при этом контент идёт в родительской секции. Парсер детектирует эту структуру и **разбивает контент родительской главы по встроенным заголовкам** (добавляя их как текст), а не плодит пустые главы.
- **Поэзия** — `<poem>/<stanza>/<v>` с переносами строк
- **Эпиграфы, цитаты, сноски** — обрабатываются
- **Rekurs лимит** — глубокая вложенность `<section>` (>2 уровня) сворачивается в контент родителя

### EpubParser

- Читает ZIP → `META-INF/container.xml` → OPF
- Манифест + spine + NCX/nav TOC
- Извлечение текста через Jsoup из XHTML
- Обложка по manifest.cover-image или meta name="cover"

### PdfParser

- Через iTextPDF 8.x
- Метаданные из `PdfDocument.documentInfo`
- Главы из закладок (outline tree) → fallback на эвристику по паттернам (`Глава`, `Chapter`, ALL CAPS)
- Финальный fallback: группировка по 10 страниц
- Graceful handling сканированных PDF (пустой текст)

### TxtParser

- Автодетекция кодировки (BOM → UTF-8/16; частотный анализ Win-1251 vs KOI8-R)
- 6 паттернов глав от специфичных к общим; выигрывает дающий 2–200 совпадений
- Size-based fallback: куски по 5000 символов с разрывом по концу предложения

---

## 6. Доменные модели

`domain/model/`:

```kotlin
data class PlaybackState(
    val isPlaying: Boolean = false,
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val totalChapters: Int = 0,
    val charOffsetInChapter: Int = 0,
    val chapterLength: Int = 0,
    val totalBookChars: Long = 0L,
    val currentBookCharOffset: Long = 0L,
    val currentChapterText: String = "",
    val coverPath: String? = null,
    val currentWordStart: Int = 0,  // Для подсветки слова
    val currentWordEnd: Int = 0
) {
    val chapterProgress: Float  // 0..1
    val bookProgress: Float     // 0..1
}

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingSeconds: Int = 0,
    val isFadingOut: Boolean = false,
    val displayTime: String = ""
)
```

---

## 7. Сервисный слой

### TtsEngine (`service/TtsEngine.kt`)

Обёртка над `android.speech.tts.TextToSpeech`:

| Метод | Назначение |
|---|---|
| `initialize(enginePackage?, onReady)` | Async-инициализация, генерация `audioSessionId`, установка `AudioAttributes(USAGE_MEDIA, SPEECH)` |
| `getInstalledEngines(): List<EngineInfo>` | Список установленных TTS-движков |
| `getCurrentEnginePackage(): String?` | Текущий пакет движка |
| `promptVoiceDownload(): Intent` | Intent для `ACTION_INSTALL_TTS_DATA` |
| `getAvailableVoices(): List<VoiceInfo>` | Голоса с `name, locale, quality, latency, requiresNetwork, features, isNotInstalled, estimatedSizeMb` |
| `getAvailableLocales(): List<Locale>` | Доступные локали |
| `setVoice(voiceName): Boolean` | Конкретный голос |
| `setLanguage(Locale): Int` | Установить язык (сбрасывает голос на дефолтный — порядок вызова важен!) |
| `setSpeechRate(0.1..4.0)` | Скорость |
| `setPitch(0.1..2.0)` | Тон |
| `setVolume(0..1)`, `setPan(-1..1)` | Передаются через `KEY_PARAM_VOLUME` / `KEY_PARAM_PAN` |
| `setSsmlEnabled(Boolean)`, `setSsmlPauseMs(0..5000)` | SSML-обёртка |
| `speak(text, utteranceId, queueMode)` | Ставит в очередь TTS |
| `chunkText(text, maxChunkSize=2000): List<TextChunk>` | Дробит по границам предложений |
| `synthesizeToFile(text, id, file)` | Рендер в WAV (для офлайн-кэша) |
| `stop()`, `shutdown()`, `isSpeaking()` | Стандарт |

**Callbacks (публикуются на произвольном TTS-thread — см. §10):**
- `onUtteranceStart(utteranceId)`
- `onUtteranceDone(utteranceId)`
- `onUtteranceError(utteranceId)`
- `onRangeStart(utteranceId, start, end)` — пословный прогресс (API 26+)

**Формат utteranceId:** `"{bookId}:{chapterIndex}:{charOffset}"` — позволяет callback'ам знать из какой главы событие.

**Audio Session ID** генерируется через `AudioManager.generateAudioSessionId()` и передаётся в TTS через `KEY_PARAM_SESSION_ID`. К этой же сессии подключаются эффекты.

### AudioEffectsManager (`service/AudioEffectsManager.kt`)

Цепочка эффектов, привязанных к audio session ID TTS:

| Эффект | Метод |
|---|---|
| `Equalizer` | `setEqualizerPreset`, `setEqualizerBandLevel` |
| `BassBoost` | `setBassBoostStrength(0..1000)` |
| `Virtualizer` | `setVirtualizerStrength(0..1000)` |
| `PresetReverb` | `setPresetReverb(preset: Short)` — 0..6 (None..Plate) |
| `LoudnessEnhancer` | `setLoudnessGain(mB)` |

**Кастомные пресеты речи** (`AudioEffectsManager.customPresets`):
- «Голос (чётче)» — [300, 0, 0, 300, 500]
- «Аудиокнига» — [200, 100, 0, 200, 300]
- «Басы», «Высокие», «Нейтральный»

**getFullState() / applyState()** — snapshot для save/restore. Обработка OEM-несовместимостей (catch + Log.w).

### SleepTimerManager (`service/SleepTimerManager.kt`)

- **Ручной старт:** `start(durationMinutes, currentVolume)`
- **Расширение:** `extend(minutes)` — +15 мин, отменяет fadeout
- **Отмена:** `cancel()`
- **Fadeout:** последние 120с громкость снижается линейно (`onVolumeChange` → TtsEngine)
- **Shake-to-extend:** акселерометр, порог 15 м/с², cooldown 2с
- **Persistent в DataStore** — `timerEndEpoch`, `fadeDuration`, `isActive` → восстановление при рестарте сервиса
- **DND интеграция** — `NotificationManager.INTERRUPTION_FILTER_PRIORITY` при срабатывании
- **Авто-расписание** через AlarmManager + `SleepTimerAlarmReceiver`

### StatsTracker (`service/StatsTracker.kt`)

- `startSession(bookId, offset)` / `endSession()` / `updateOffset(globalOffset)`
- Пишет `ListeningSessionEntity` при завершении сессии
- Отслеживает чистое время прослушивания (без пауз)

### SsmlBuilder (`service/SsmlBuilder.kt`)

- `wrapWithPauses(text, pauseMs)` — разбивка на предложения + `<break time="...">` + `<speak>`
- `splitIntoSentences(text)` — сплит по `.!?…` + пробел + заглавная буква
- XML-escaping спецсимволов

### TtsPlaybackService (`service/TtsPlaybackService.kt`) — **ГЛАВНЫЙ ОРКЕСТРАТОР**

Всё, что связано с воспроизведением — здесь. ~1300 строк, самый критичный файл проекта.

**Жизненный цикл:**
1. `onCreate()` — инициализация всех менеджеров, setupMediaSession, регистрация BECOMING_NOISY receiver
2. `onBind()` — возвращает `LocalBinder`
3. `onStartCommand()` — обработка action-интентов (PLAY, PAUSE, STOP, NEXT, PREV, SLEEP_TIMER) + MEDIA_BUTTON. **Первым делом** вызывает `startForeground()`
4. `onDestroy()` — сохранение позиции (GlobalScope), отмена всех jobs, unregister receiver, release всех ресурсов

**Публичный API:**

| Метод | Описание |
|---|---|
| `playBook(bookId, chapterIndex, charOffset)` | Загружает книгу, начинает воспроизведение |
| `pause()` | Атомарная остановка (см. §8) |
| `resume()` | Продолжение с текущей позиции |
| `nextChapter()`, `prevChapter()` | Переходы между главами (3% правило для prev) |
| `seekToChapter(index)`, `seekByCharOffset(delta)`, `seekToAbsoluteCharOffset(offset)`, `seekToBookProgress(0..1)` | Различные seek-операции |
| `seekBackOneSentence()` | Откат на одно предложение |
| `resyncPlayback()` | Re-speak после изменения voice/rate/pitch во время play |
| `startSleepTimer(min)`, `extendSleepTimer(min)`, `cancelSleepTimer()` | Таймер |
| `reinitializeTts(enginePackage, onReady)` | Переключение TTS-движка |
| `setResumeAfterPreview(Boolean)` | Для preview в VoiceSettings |
| `getTtsEngine()`, `getAudioEffectsManager()` | Для UI доступа |

**Flows наружу:**
- `playbackState: StateFlow<PlaybackState>`
- `sleepTimerState: StateFlow<SleepTimerState>`
- `speechRateFlow: StateFlow<Float>`

---

## 8. Жизненный цикл Play / Pause

Этот раздел документирует **выстраданные инварианты** после 6 раундов аудита (~20 раскопанных багов, в т.ч. тонкие thread-races).

### 8.1 Трекинг async-пайплайнов

Все пути, которые собираются запустить воспроизведение, трекаются в одном из двух jobs:

```kotlin
private var currentLoadJob: Job? = null   // playBook/resumeLastPlayedBook/resume-init-wait
private var currentSpeakJob: Job? = null  // speakChapter's coroutine
```

**Инвариант:** после `pause()` оба `null` или cancelled. Никакая оставшаяся async-работа не может форсить `isPlaying=true`.

### 8.2 `speakChapter` — ключевой путь

```kotlin
private fun speakChapter(chapterIndex: Int, startCharOffset: Int = 0) {
    currentSpeakJob?.cancel()          // Отменяем предыдущий
    ttsEngine.stop()
    val chapter = chapters.getOrNull(chapterIndex) ?: return

    currentSpeakJob = serviceScope.launch {
        try {
            val processedText: String
            val chunks: List<TtsEngine.TextChunk>
            withContext(Dispatchers.Default) {
                processedText = TextProcessor.DEFAULT.process(chapter.textContent)
                ensureActive()                                    // Guard 1
                chunks = ttsEngine.chunkText(processedText)
            }
            ensureActive()                                        // Guard 2
            // ... assign currentChunks, emit state ...
            for (i in containingIdx until chunks.size) {
                ensureActive()                                    // Guard 3 (на каждую итерацию)
                ttsEngine.speak(chunks[i].text, utteranceId)
            }
            if (anyQueued) startWatchdog()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "speakChapter failed", e) }
    }
}
```

**Принципы:**
1. Единственный источник правды — **coroutine cancellation**. `ensureActive()` стоит на каждой границе длительной работы.
2. **Никогда не читаем `isPlaying` из корутины** — это расовое условие с другими callback'ами.
3. Тяжёлая обработка текста — на `Dispatchers.Default`. Main thread не блокируется.
4. Catch `CancellationException` и re-throw — чтобы механизм корутин правильно завершил отмену.

### 8.3 `pause()` — атомарный

```kotlin
fun pause() {
    pausedByTransientFocusLoss = false   // Сброс transient-flag
    currentLoadJob?.cancel()              // Все async-пайплайны отменены
    currentLoadJob = null
    currentSpeakJob?.cancel()
    currentSpeakJob = null
    ttsEngine.stop()
    stopWatchdog()
    _playbackState.update { it.copy(isPlaying = false) }
    updateMediaSession()
    updateNotification()
    savePosition()          // GlobalScope — переживает onDestroy
    statsTracker.endSession()
    // НЕ отпускаем audio focus — Spotify-style, чтобы не потерять headset-приоритет
}
```

### 8.4 `resume()` — идемпотентен

```kotlin
fun resume() {
    pausedByTransientFocusLoss = false   // Сброс flag (ручной resume блокирует auto-resume)
    if (isPlaying && currentSpeakJob?.isActive == true) return   // Уже играет — no-op
    if (bookId == null || chapters.isEmpty()) return

    if (!ttsEngine.initState.value) {
        // TTS ещё инициализируется — ждём через currentLoadJob
        currentLoadJob?.cancel()
        currentLoadJob = serviceScope.launch {
            ttsEngine.initState.first { it }
            ensureActive()
            doResume()
        }
        return
    }
    doResume()
}

private fun doResume() {
    val state = _playbackState.value    // Читаем state FRESH (не snapshot!)
    val bookId = state.bookId ?: return
    _playbackState.update { it.copy(isPlaying = true) }
    requestAudioFocus()
    statsTracker.startSession(bookId, state.currentBookCharOffset)
    speakChapter(state.chapterIndex, state.charOffsetInChapter)
    updateNotification()
}
```

### 8.5 Guard'ы в TTS callbacks

Callbacks от TTS-движка приходят на произвольном thread'е → **все хопаются на Main через serviceScope.launch**.

**`onUtteranceDone`** проверяет:
1. `if (!isPlaying) return` — пауза приоритетна
2. `if (chapterIdx != currentChapterIndex) return` — игнорировать stale callback от покинутой главы

**`onRangeStart`** (обновление позиции при каждом слове) проверяет то же самое.

**`onUtteranceError`** только логирует и вызывает `markTtsProgress()` — **не** маршрутизирует в `onUtteranceDone` (иначе `ttsEngine.stop()` триггерил auto-advance сразу после паузы).

### 8.6 Watchdog (защита от зависания)

```kotlin
watchdogJob = serviceScope.launch {
    while (isActive) {
        delay(5_000)
        ensureActive()
        if (!isPlaying) continue
        if (now - lastTtsProgressAt < 20_000) continue

        // Никакого прогресса 20с — форсим advance
        ensureActive()
        if (!isPlaying) continue   // Двойная проверка перед speakChapter
        if (currentChapterIndex < chapters.size - 1) {
            currentChapterIndex++
            updateChapterState()
            speakChapter(currentChapterIndex)
        } else pause()
    }
}
```

`lastTtsProgressAt: @Volatile Long` — обновляется из TTS-threads, читается из watchdog'а.

### 8.7 Auto-resume после transient focus loss

```kotlin
AUDIOFOCUS_LOSS              → pause() + clear transient flag
AUDIOFOCUS_LOSS_TRANSIENT    → pause() + set transient flag (для auto-resume)
AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK → duck volume to 30%
AUDIOFOCUS_GAIN              → restore volume + if (transient flag) resume()
```

`pause()` **сбрасывает** transient flag — если пользователь ручно ставит на паузу во время звонка, auto-resume после звонка не сработает.

### 8.8 ACTION_AUDIO_BECOMING_NOISY

Наушники выдернули / Bluetooth отключили → `BroadcastReceiver` вызывает `pause()`. Стандартное поведение всех приличных media-приложений.

---

## 9. Приоритет MediaSession и маршрутизация гарнитуры

Система Android выбирает какую MediaSession слушать при нажатии кнопок гарнитуры на основе сложной эвристики. Чтобы **наша** сессия побеждала другие (включая «мёртвые» кэшированные сессии других приложений), нужны **все** нижеперечисленные пункты одновременно:

### 9.1 android:appCategory="audio" в манифесте

```xml
<application android:appCategory="audio" ...>
```

Без этого OS не классифицирует нас как media-app → пониженный приоритет.

### 9.2 AudioAttributes на TextToSpeech

```kotlin
tts.setAudioAttributes(
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
)
```

Без этого TTS играет через USAGE_ASSISTANT — и система **не считает нас media-playback**.

### 9.3 Silent AudioTrack Anchor

**Ключевая проблема:** Google TTS играет звук из **своего процесса** (`com.google.android.tts`), не из нашего. `AudioPlaybackConfiguration` системы видит Google TTS как audio producer, не ABook. Наша сессия может быть STATE_PLAYING, но media-кнопки уходят мимо.

**Решение** (стандарт для всех серьёзных TTS-приложений):

```kotlin
private fun startSilentAudioAnchor() {
    silentAudioJob = serviceScope.launch(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(USAGE_MEDIA / SPEECH)
            .setAudioFormat(44100 Hz, mono, PCM_16BIT)
            .setTransferMode(MODE_STREAM)
            .build()
        track.setVolume(0f)
        track.play()
        silentAudioTrack = track
        val silence = ShortArray(bufSize / 2)
        while (isActive) {
            silentAudioTrack?.write(silence, 0, silence.size) ?: break
        }
    }
}
```

Беззвучный PCM-поток на volume=0 создаёт `AudioPlaybackConfiguration` с нашим package → система видит ABook как **настоящий audio producer**. Включается при `playBook()`, работает через pause/resume, останавливается только в `onDestroy()`.

### 9.4 Держать audio focus даже на паузе

```kotlin
fun pause() {
    // ...
    // НЕ отпускаем audio focus.
}
```

Spotify/YouTube Music держат focus через pause. Отпускание = «я больше не медиа-приложение» → другое приложение мгновенно перехватит media-кнопки. Focus отпускается **только на onDestroy**.

### 9.5 Ongoing notification пока книга загружена

```kotlin
.setOngoing(state.bookId != null)   // НЕ зависит от isPlaying
```

Если notification swipable и пользователь смахивает его на паузе — сервис вылетает из foreground, MediaSession становится inactive, media-кнопки уходят. Держим ongoing пока есть загруженная книга.

### 9.6 MediaButtonReceiver + PendingIntent

```kotlin
// В onCreate:
val mediaButtonPi = PendingIntent.getBroadcast(
    this, 0,
    Intent(ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver::class.java),
    FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
)
mediaSession.setMediaButtonReceiver(mediaButtonPi)
```

**В манифесте** нужны **оба**:

```xml
<service android:name=".service.TtsPlaybackService" android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_BUTTON" />
    </intent-filter>
</service>

<receiver android:name="androidx.media.session.MediaButtonReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_BUTTON" />
    </intent-filter>
</receiver>
```

`MediaButtonReceiver.onReceive()` ищет сервис с фильтром `ACTION_MEDIA_BUTTON` через `PackageManager.queryIntentServices()` — **без фильтра на сервисе он делает fallback на MediaBrowserServiceCompat** (у нас это AutoMediaBrowserService для Android Auto — неправильный сервис).

### 9.7 Notification transport controls через MediaButtonReceiver

```kotlin
NotificationCompat.Action(
    icon, label,
    MediaButtonReceiver.buildMediaButtonPendingIntent(
        context, PlaybackStateCompat.ACTION_PLAY_PAUSE
    )
)
```

Это связывает notification с нашей сессией для системы. Boost приоритета. Без этого система не знает какую сессию attached к notification.

### 9.8 Ручной разбор KeyEvent в onMediaButtonEvent

На некоторых OEM-сборках (Samsung, Xiaomi) `super.onMediaButtonEvent()` молча глотает `PLAY_PAUSE` если `PlaybackState` не полностью синхронизирован. Разбираем KeyEvent сами:

```kotlin
override fun onMediaButtonEvent(intent: Intent?): Boolean {
    val keyEvent: KeyEvent = intent?.getParcelableExtra(EXTRA_KEY_EVENT) ?: return super.onMediaButtonEvent(intent)
    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
        when (keyEvent.keyCode) {
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_HEADSETHOOK -> {
                if (isPlaying) pause() else resume()
                return true
            }
            KEYCODE_MEDIA_PLAY      -> { resume(); return true }
            KEYCODE_MEDIA_PAUSE     -> { pause(); return true }
            KEYCODE_MEDIA_NEXT      -> { nextChapter(); return true }
            KEYCODE_MEDIA_PREVIOUS  -> { prevChapter(); return true }
            KEYCODE_MEDIA_STOP      -> { pause(); return true }
        }
    }
    return super.onMediaButtonEvent(intent)
}
```

### 9.9 Re-assert isActive в updateMediaSession

```kotlin
if (!mediaSession.isActive) mediaSession.isActive = true
```

После audio focus loss на некоторых сборках сессия переходит в inactive — восстанавливаем на каждом state-update.

### 9.10 POST_NOTIFICATIONS runtime permission (Android 13+)

Без разрешения foreground-notification невидимо → MediaStyle не отображается → OS считает нас backgrounded → media-кнопки уходят к другому. В MainActivity.onCreate запрашиваем через `ActivityResultContracts.RequestPermission`.

### 9.11 Авто-загрузка последней книги при headset Play

```kotlin
override fun onPlay() {
    if (currentBookId == null || chapters.isEmpty()) {
        resumeLastPlayedBook()   // Читает последнюю из DB, playBook(...)
    } else {
        resume()
    }
}
```

Заодно guard против двойного запуска:

```kotlin
private fun resumeLastPlayedBook() {
    if (currentLoadJob?.isActive == true) return
    currentLoadJob = serviceScope.launch {
        try {
            val book = bookDao.getLastOpenedBook() ?: return@launch
            ensureActive()
            val pos = bookDao.getPosition(book.id)
            ensureActive()
            playBook(book.id, pos?.chapterIndex ?: 0, pos?.charOffsetInChapter ?: 0)
        } catch (e: CancellationException) { throw e }
    }
}
```

---

## 10. Thread safety

### 10.1 Инвариант: всё на Main thread

Весь сервисный код — `pause()`, `resume()`, `playBook()`, `speakChapter()`, `updateMediaSession()`, `buildNotification()`, мутации `currentChapterIndex`, `chapters`, `currentChunks` — **выполняется только на Main thread**.

### 10.2 Источники callback'ов и их dispatcher'ы

| Источник | Thread по умолчанию | Наше решение |
|---|---|---|
| UI `onClick` (Compose) | Main | — |
| `service?.pause()` из ViewModel | Main (viewModelScope.Main.immediate) | — |
| `MediaSessionCompat.Callback` | Main (default handler) | — |
| `BroadcastReceiver.onReceive` (Noisy) | Main | — |
| `AudioFocusRequest.onAudioFocusChange` | Main | — |
| `SleepTimerManager.onTimerExpired` | Main (serviceScope) | — |
| **TTS `UtteranceProgressListener`** | **Произвольный** | Все handler'ы hop'ят через `serviceScope.launch { ... }` |
| AlarmManager PendingIntent | Main | — |

### 10.3 Поля с @Volatile

```kotlin
@Volatile private var lastTtsProgressAt: Long = 0L  // writes от TTS-threads, reads с Main
```

Long writes на 32-битных JVM могут быть torn, `@Volatile` гарантирует атомарность + visibility.

### 10.4 Потокобезопасность Android API

| API | Thread-safe? |
|---|---|
| `MutableStateFlow.update { }` | Yes (CAS) |
| `mediaSession.setPlaybackState` | Yes |
| `mediaSession.setMetadata` | Yes |
| `NotificationManager.notify` | Yes |
| **`TextToSpeech` методы** | **Нужно с того же thread, что создали** (мы создаём на Main → все вызовы на Main) |
| `AudioTrack.write` | Yes (мы пишем только из одного IO-потока) |
| Room suspend queries | Хопают на Room IO pool |

### 10.5 GlobalScope для critical persistence

`savePosition()` использует `GlobalScope.launch(IO)` с `@OptIn(DelicateCoroutinesApi)` — write переживает `serviceScope.cancel()` в `onDestroy()`. Это единственное место в проекте, где используется GlobalScope.

---

## 11. Пайплайн обработки текста перед TTS

`service/textprocessing/TextProcessor.kt` — координатор:

```kotlin
class TextProcessor(
    expandAbbreviations: Boolean = true,
    removeFootnotes: Boolean = true,
    normalizePunctuation: Boolean = true,
    customPronunciations: Map<String, String> = emptyMap()
) {
    fun process(text: String): String = text
        .let(PunctuationMapper::normalize)
        .let(FootnoteHandler::removeFootnotes)
        .let(AbbreviationExpander::expand)
        .let { applyCustomPronunciations(it) }
}
```

### Компоненты

- **`AbbreviationExpander`** — 30+ замен (`т.д.` → `так далее`, `т.е.` → `то есть`, `см.` → `смотри`, ...)
- **`FootnoteHandler`** — удаление `[1]`, `[N]` сносок
- **`PunctuationMapper`** — нормализация тире, многоточий, кавычек (SSML-совместимо)
- **Кастомный словарь** — пользовательские замены через регулярное выражение `\b<word>\b`

Оффсеты `processedText` и `originalText` отличаются → `speakChapter` хранит оба и конвертирует для book-progress через пропорцию.

---

## 12. UI-слой (Compose)

### Навигация (`ui/navigation/`)

| Файл | Назначение |
|---|---|
| `Screen.kt` | Sealed class маршрутов (Library, Player, VoiceSettings, ChapterList, Bookmarks, Settings) |
| `ABookNavHost.kt` | NavHost + bottom nav (3 вкладки: Библиотека / Плеер / Голос) |
| `NavViewModel.kt` | Хранит `lastPlayedBookId: StateFlow<String?>` из БД — для вкладки «Плеер» |

### Экран библиотеки (`ui/library/`)

- FAB «+» → `ACTION_OPEN_DOCUMENT` (MIME: fb2, epub, txt, pdf)
- `takePersistableUriPermission` для долгосрочного доступа
- Карточки книг: иконка, title/author, format-бейдж, глав, дата, кнопка удаления
- **Импорт** через `LibraryViewModel.importBook(uri)` → определение формата → соответствующий `BookParser` → сохранение в Room + обложка на disk

### Экран плеера (`ui/player/`)

- Обложка + title + chapter info
- Два прогресс-бара (chapter + book) с %
- Контролы: `SkipPrevious`, `Replay(-30s)`, Play/Pause (72dp), `Forward(+30s)`, `SkipNext`
- `seekBySeconds(seconds)` — использует WPM×speechRate×chars-per-word для перевода секунд в `charOffset`
- Таймер сна: кнопка `Bedtime`. Одно нажатие → 30 мин. Повторное → BottomSheet (15/30/45/60/90/+15/cancel)
- Закладки: `AddBookmark` icon + navigation → Bookmarks screen
- `LaunchedEffect(bookId)` + 200ms delay → `viewModel.playBook(bookId)` на первом входе

### Экран настроек голоса (`ui/voicesettings/`)

Прокручиваемый экран с секциями:

1. **Профили** — горизонтальный ряд карточек + Сохранить + Удалить. **Активный профиль persist'ится** в `AppPreferences.defaultVoiceProfileId` и auto-применяется при следующем запуске
2. **Основные параметры** — слайдеры rate / pitch / volume / pan с `onValueChangeFinished = applyLivePlaybackChanges()`
3. **Выбор голоса** — `VoicePickerDialog` с фильтром по языку (persist'ится в `voice_lang_filter`) + бейджи качества + кнопка «Скачать»
4. **TTS-движок** — Dropdown со списком `getInstalledEngines()` + reinit через `service.reinitializeTts(packageName)`
5. **Эквалайзер** — пресеты + ручные слайдеры на частотные полосы
6. **Bass Boost / Virtualizer** — слайдеры 0..100%
7. **Reverb / Loudness** — dropdown / slider
8. **Кастомные EQ пресеты** — чипы с готовыми настройками для речи
9. **SSML** — Switch + slider длительности паузы
10. **Предпрослушка** — тестовая фраза (id="preview" → авто-резьюм книги если играла)

**Важно про порядок применения профиля:**
```kotlin
// ORDER MATTERS:
setLanguage(locale)   // сначала язык — сбрасывает голос на default locale
setVoice(voiceName)   // потом конкретный голос (имеет приоритет)
```

### Экран глав (`ui/chapters/`)

LazyColumn с номером + названием. Текущая глава выделена primary-цветом. Нажатие → `popBackStack` + `selectedChapter` через `savedStateHandle` → `viewModel.seekToChapter(index)`.

### Экран закладок (`ui/bookmarks/`)

Список + добавление с label + удаление + переход.

### Экран настроек (`ui/settings/`)

- Тема (light/dark/auto/amoled) — `ThemeViewModel`
- Язык (system/ru/en)
- Seek short/long seconds
- Keep screen on toggle
- About

### Тема (`ui/theme/`)

Material 3. Dynamic color на Android 12+. Dark/Light по системе или ручное через `ThemeViewModel` (читает `AppPreferences.themeMode`). AMOLED — чёрный фон.

---

## 13. Dependency Injection (Hilt)

`di/AppModule.kt` (`@InstallIn(SingletonComponent)`):

- `provideDatabase()` → `ABookDatabase` singleton
- `provideBookDao()`, `provideBookmarkDao()`, `provideStatsDao()`, `provideVoiceProfileDao()`, `provideSleepScheduleDao()`
- `provideAppPreferences()` → `AppPreferences(context)`
- `@ApplicationContext` везде где нужен context

Сервисы и ViewModels аннотированы `@AndroidEntryPoint` / `@HiltViewModel`.

---

## 14. Разрешения (`AndroidManifest.xml`)

| Разрешение | Зачем |
|---|---|
| `FOREGROUND_SERVICE` | Фоновое воспроизведение |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Тип foreground service на Android 14+ |
| `POST_NOTIFICATIONS` | Notification на Android 13+ (runtime) |
| `SCHEDULE_EXACT_ALARM` | Авто-таймер сна через AlarmManager |
| `WAKE_LOCK` | TTS во время screen off |
| `HIGH_SAMPLING_RATE_SENSORS` | Акселерометр для shake-to-extend (API 31+) |
| `RECEIVE_BOOT_COMPLETED` | Восстановление persistent-алармов после перезагрузки |
| `ACCESS_NOTIFICATION_POLICY` | DND при срабатывании sleep timer |
| `VIBRATE` | Вибро-предупреждение перед fadeout |

Все ключевые настройки манифеста:

```xml
<application android:appCategory="audio" android:name=".ABookApplication" ...>
    <service android:name=".service.TtsPlaybackService"
             android:exported="false"
             android:foregroundServiceType="mediaPlayback">
        <intent-filter>
            <action android:name="android.intent.action.MEDIA_BUTTON" />
        </intent-filter>
    </service>

    <receiver android:name="androidx.media.session.MediaButtonReceiver"
              android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MEDIA_BUTTON" />
        </intent-filter>
    </receiver>

    <service android:name=".service.AutoMediaBrowserService"
             android:exported="true">
        <intent-filter>
            <action android:name="android.media.browse.MediaBrowserService" />
        </intent-filter>
    </service>

    <receiver android:name=".service.SleepTimerAlarmReceiver" android:exported="false" />
    <receiver android:name=".service.BootReceiver" android:exported="true"
              android:enabled="true">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        </intent-filter>
    </receiver>
</application>
```

---

## 15. Сборка и CI

### Локальная сборка

```bash
export JAVA_HOME="/c/Users/Q/jdk-17"
./gradlew assembleDebug
```

**Автокопия APK:** `app/build.gradle.kts` настроен так, что после каждого `assemble<Variant>` APK копируется в корень проекта как `abook-<variant>.apk` (см. [PR #c817e59](https://github.com/einroot/abook/commit/c817e59)).

### Тесты

```bash
./gradlew testDebugUnitTest
```

Текущий покрытие:
- `Fb2ParserTest` — 7 тестов, включая регрессионный тест на реальном файле `sre-real.fb2` (113 глав, inline-headings pattern)
- `TxtParserTest` — кодировки, паттерны глав, edge cases

### GitHub Actions (`.github/workflows/ci.yml`)

На каждый push в `main` и PR:
1. Checkout
2. Setup JDK 17 (Temurin)
3. Setup Gradle (с кэшированием)
4. `./gradlew testDebugUnitTest`
5. `./gradlew assembleDebug`
6. Upload APK artifact

---

## 16. Зависимости (`gradle/libs.versions.toml`)

| Библиотека | Версия | Назначение |
|---|---|---|
| Kotlin | 2.1.0 | Язык + KSP |
| Compose BOM | 2024.12.01 | UI-фреймворк |
| Material 3 | (BOM) | Тема, компоненты |
| Material Icons Extended | (BOM) | Иконки |
| Navigation Compose | 2.8.5 | Навигация |
| Lifecycle ViewModel | 2.8.7 | ViewModel + Compose |
| Hilt | 2.53.1 | DI |
| Hilt Navigation Compose | 1.2.0 | `hiltViewModel()` |
| Room | 2.6.1 | ORM |
| Media | 1.7.0 | MediaSessionCompat |
| Coroutines | 1.9.0 | Async |
| DataStore | 1.1.1 | Preferences |
| Jsoup | 1.18.3 | EPUB HTML |
| iTextPDF | 8.0.5 | PDF |
| JUnit / MockK / Coroutines Test | — | Тесты |

---

## 17. Диагностика и отладка

### Проверка routing'а media-кнопок

```bash
adb logcat -c
adb logcat -s TtsPlaybackService:D
# Нажмите кнопку на гарнитуре
```

В логе должно быть:
```
D TtsPlaybackService: onStartCommand action=android.intent.action.MEDIA_BUTTON
D TtsPlaybackService: MEDIA_BUTTON intent: keyCode=85 action=0
D TtsPlaybackService: MediaButtonReceiver.handleIntent -> true
D TtsPlaybackService: onMediaButtonEvent: key=85 action=0
D TtsPlaybackService: MediaSession.onPause
```

Если нет первой строки — событие не доходит до сервиса (другое приложение перехватывает или MediaButtonReceiver не зарегистрирован). Если доходит до `onStartCommand`, но нет `onMediaButtonEvent` — проблема с MediaSession dispatch.

### Как "забрать" приоритет у чужой MediaSession

После установки APK один раз **явно нажмите Play в UI приложения** — это регистрирует нашу сессию как самую свежую active audio session. После этого система будет маршрутизировать кнопки нам, пока другое приложение не запустит свой play.

### Watchdog в логе

Если TTS внезапно замолчал, watchdog через 20 секунд напишет:
```
W TtsPlaybackService: Watchdog: no TTS progress for 20123ms on chapter 5. Forcing advance.
```

### Silent audio anchor

В логе сборки/запуска:
```
D TtsPlaybackService: Silent audio anchor started
```

Если его нет — MediaSession приоритет будет слабее.

---

## 18. История изменений

Полный changelog — см. [git log](https://github.com/einroot/abook/commits/main). Ключевые вехи:

### Первоначальный MVP (`a0b4f0c`)
Базовая функциональность: play/pause, таймер сна, голосовые настройки, TTS через Google TTS, MediaSessionCompat, уведомление, 4 парсера форматов.

### Парсеры форматов (коммиты категорий 1–4)
Реальные парсеры FB2 (XmlPullParser + cover extraction + inline-heading pattern), EPUB (Jsoup + TOC), PDF (iTextPDF + bookmarks), улучшенный TXT.

### Text processing pipeline (категория 15)
`TextProcessor` — `AbbreviationExpander` + `FootnoteHandler` + `PunctuationMapper` + custom pronunciations.

### Bookmarks / Stats / Android Auto (категории 8, 12, 13)
`BookmarksScreen`, `StatsTracker`, `AutoMediaBrowserService`.

### 6 раундов аудита Play / Pause / Headset (коммиты `03a87d0` … `c2300c8`)

Суммарно найдено и исправлено ~20 багов:

| Коммит | Что исправлено |
|---|---|
| `03a87d0` | Audio focus не отпускается на паузе (как Spotify) |
| `8978119` | Silent AudioTrack anchor для audio producer priority |
| `e463185` | Порядок `setLanguage` → `setVoice` при загрузке профиля |
| `726472f` | Persist active voice profile + unfreeze Play |
| `7396d15` | Race: async `speakChapter` queuing TTS **после** pause |
| `3d780cf` | Removed fragile `isPlaying` check inside coroutine — cancellation only |
| `b24e4e5` | `onUtteranceDone` auto-advance после pause через error→done routing |
| `cc410cf` | Watchdog race + AudioFocusRequest listener leak + notif bitmap cache |
| `ed6c1d0` | `currentLoadJob` — tracking playBook/resumeLastPlayedBook/resume-init-wait |
| `109188d` | `doResume` читает state fresh, не stale snapshot |
| `8aacafb` | ACTION_AUDIO_BECOMING_NOISY receiver + onRangeStart guards + auto-resume after transient focus |
| `ea62f74` | TTS callback thread safety (hop to Main) + `resumeLastPlayedBook` pause race |
| `c2300c8` | `resume` идемпотентен + `savePosition` через GlobalScope (переживает destroy) |

### Архитектурные инварианты после 6 раундов

1. **Все async-пайплайны воспроизведения трекаются** в `currentLoadJob` / `currentSpeakJob`
2. **`pause()` атомарно отменяет** оба + сбрасывает transient-flag
3. **`speakChapter`** полагается **только** на coroutine cancellation как источник правды
4. **`ensureActive()`** после каждой suspend-границы
5. **Stale callback guards** — chapterIdx match + isPlaying check в `onUtteranceDone`/`onRangeStart`
6. **`onUtteranceError`** только логирует (нет error→done routing)
7. **Системные audio события** (noisy, focus loss/gain transient) обработаны
8. **MediaSession priority** выстроена через 11 точек (§9)
9. **Все TTS callback'и hop'ят на Main** через serviceScope.launch
10. **@Volatile для lastTtsProgressAt** (writes с TTS-threads)
11. **Cover bitmap кэшируется**, AudioFocusRequest переиспользуется
12. **GlobalScope** для critical persistence (`savePosition`)
13. **`doResume` читает state свежим** — никаких stale snapshot'ов

---

## Лицензия

TBD.

## Контрибьюторы

- @einroot — автор
- Claude Opus 4.6 (1M context) — co-author большинства коммитов после a0b4f0c

---

> Документ отражает состояние проекта на коммит `c2300c8`.
> При существенных изменениях — обновить этот файл.
