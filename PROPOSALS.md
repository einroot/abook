# ABook — Предложения по развитию

> Дорожная карта и идеи. Чекбоксы отмечают уже реализованное.
> **Обновлено:** после 6 раундов аудита Play/Pause (коммит `c2300c8`, 68 коммитов в `main`).

**Легенда:**
- ✅ — реализовано и в проде
- 🟡 — частично реализовано
- ⬜ — не сделано

---

## 1. ГОЛОС И TTS (TOP PRIORITY)

### 1.1 Расширение настроек голоса

- ⬜ **Скорость по расписанию** — автоматическое замедление речи ночью (0.8x после 22:00) и ускорение днём
- ✅ **Chips быстрого переключения скорости** — `SpeedToggleRow` с пресетами 0.5/0.75/1.0/1.25/1.5/2.0x
- ⬜ **Пресеты скорости "по ситуации"** — «Ходьба» (1.2x), «Засыпание» (0.8x), «Фоновое прослушивание» (1.5x), «Изучение» (0.7x)
- 🟡 **Тонкая настройка пауз** — есть общая пауза SSML между предложениями. Нет отдельных паузы после запятой, точки, конца абзаца
- 🟡 **SSML prosody для эмоций** — базовая обёртка `<break>` есть, `<emphasis>` / `<prosody>` ещё не используются
- ⬜ **Авто-пауза на диалогах** — детектировать прямую речь и добавлять микропаузу
- ⬜ **Голосовые шаблоны для персонажей** — разные pitch/rate для реплик разных персонажей
- ⬜ **Нормализация громкости**
- ✅ **Реверберация (PresetReverb)** — 0..6 (None/SmallRoom/MediumRoom/LargeRoom/MediumHall/LargeHall/Plate)
- ✅ **LoudnessEnhancer** — усиление громкости без клиппинга (API 19+)
- 🟡 **Запись в аудиофайл** — `TtsEngine.synthesizeToFile()` реализован как API, UI для массовой генерации ещё нет
- ✅ **Поддержка нескольких TTS-движков** — `getInstalledEngines()` + переключение через `reinitializeTts(enginePackage)`
- ⬜ **A/B тестирование голосов**
- ✅ **Фильтр голосов по языку** — `voiceLanguageFilter` persist в DataStore + UI в VoicePickerDialog
- ✅ **Индикаторы качества голоса** — бейджи quality / latency / online / offline
- ✅ **Promp voice download** — кнопка «Скачать голосовые данные» через `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA`
- ⬜ **Автоматический выбор лучшего голоса по языку книги**

### 1.2 Эквалайзер и эффекты

- ✅ **Equalizer** — пресеты + ручные полосы (см. `AudioEffectsManager`)
- ✅ **BassBoost** — 0..1000
- ✅ **Virtualizer (пространственный звук)** — 0..1000
- ✅ **Custom EQ presets для речи** — «Голос (чётче)», «Аудиокнига», «Басы», «Высокие», «Нейтральный»
- ✅ **Save / restore state** — `getFullState()` / `applyState()` для profile persist
- ⬜ **Параметрический EQ** (Q-фактор)
- ⬜ **Визуализация частотного спектра**
- ⬜ **Компрессор динамического диапазона**
- ⬜ **Denoise**

### 1.3 Профили голоса

- ✅ **CRUD профилей** — создание, загрузка, удаление
- ✅ **Persist активного профиля** — `defaultVoiceProfileId` в DataStore, auto-применяется при следующем запуске
- ✅ **Правильный порядок** `setLanguage` → `setVoice` при загрузке (иначе `setLanguage` сбрасывает voice)
- ⬜ **Профиль привязанный к книге** — auto-switch при открытии
- ⬜ **Профиль привязанный к жанру**
- ⬜ **Экспорт/импорт профилей (JSON)**
- ⬜ **Профиль по Bluetooth-устройству**
- ⬜ **«Голос дня»**

---

## 2. ПАРСИНГ И ФОРМАТЫ КНИГ

### 2.1 Парсеры (реализованы)

- ✅ **FB2-парсер** (`Fb2Parser.kt`) — XmlPullParser, метаданные, обложка из `<binary>`, поэзия, эпиграфы, **inline-headings pattern** (пустые `<section>` как встроенные заголовки)
- ✅ **EPUB-парсер** (`EpubParser.kt`) — ZipInputStream + OPF + NCX/nav TOC + Jsoup
- ✅ **PDF-парсер** (`PdfParser.kt`) — iTextPDF, bookmarks → главы, fallback по эвристике
- ✅ **TXT-парсер** (`TxtParser.kt`) — авто-детекция кодировки (UTF-8, Windows-1251, KOI8-R), 6 паттернов для заголовков
- ✅ **Регрессионные тесты** — `Fb2ParserTest` на реальном файле с 113 главами
- ⬜ **DJVU** — через OCR или текстовый слой
- ⬜ **MOBI / AZW3** — Kindle
- ⬜ **DOC / DOCX** — Word
- ⬜ **RTF, HTML**

### 2.2 Интеллектуальное разбиение на главы

- ✅ **6 паттернов заголовков в TXT** — «Глава», «Chapter», «Part», «Часть», римские цифры, ALL CAPS
- ✅ **Размерный fallback** — куски по 5000 символов с разрывом по концу предложения
- ✅ **Inline-headings в FB2** — разбивка родительского контента по встроенным пустым секциям
- ⬜ **ML-детекция глав**
- ⬜ **Ручная разбивка**
- ⬜ **Распознавание TOC-страницы в тексте**

### 2.3 Обработка текста перед озвучкой

- ✅ **Замена аббревиатур** — `AbbreviationExpander` с 30+ заменами
- ✅ **Замена пунктуации** — `PunctuationMapper` (тире, многоточия, кавычки)
- ✅ **Удаление сносок** — `FootnoteHandler` для `[1]`, `[N]`
- ✅ **Словарь произношений** — `customPronunciations: Map<String, String>` через `TextProcessor`
- 🟡 **UI для словаря произношений** — data layer готов, экран `PronunciationDictScreen` ещё не сделан
- ⬜ **Пропуск колонтитулов и номеров страниц для PDF**
- ⬜ **Детекция языка по абзацам** (мультиязычные книги)
- ⬜ **Детекция диалогов** — отдельные segments для NARRATION / DIALOGUE

---

## 3. ТАЙМЕР ЗАСЫПАНИЯ

- ✅ **Ручной запуск + пресеты** — 15/30/45/60/90 мин
- ✅ **Fadeout последние 120с** — линейное снижение громкости
- ✅ **Расширение таймера** (+15 мин)
- ✅ **Shake-to-extend** — акселерометр, порог 15 м/с², cooldown 2с
- ✅ **Persistent в DataStore** — выживает перезапуск сервиса
- ✅ **AlarmManager backup** — через `SleepTimerAlarmReceiver`
- ✅ **DND интеграция** — включение Do Not Disturb при срабатывании
- ✅ **BootReceiver** — восстановление persistent-алармов после ребута
- 🟡 **Настраиваемая длительность fadeout** — есть в entity, нет UI
- ⬜ **Таймер по главам** — «остановить после 3 глав»
- ⬜ **Расписание на каждый день недели**
- ⬜ **Будильник-пробуждение** — начать читать в заданное время утром
- ⬜ **Fade-in при пробуждении**
- ⬜ **«Умное засыпание»** — детекция отсутствия взаимодействия
- ⬜ **«Вы ещё слушаете?»** — подтверждение каждые N минут
- ⬜ **История таймера** — статистика во сколько обычно засыпает

---

## 4. НАВИГАЦИЯ И ПРОГРЕСС

### 4.1 Навигация

- ✅ **Перемотка по времени** — `seekBySeconds(seconds)` использует WPM × rate × chars/word → charOffset
- ✅ **Настраиваемая длина перемотки** — `seekShortSeconds` (default 30с) и `seekLongSeconds` (default 300с) в DataStore
- 🟡 **Перетаскиваемый seek bar** — сделан для главы и книги
- ⬜ **Мини-карта текста** — визуализация всей книги как полоски
- ⬜ **Поиск по тексту**
- ✅ **Закладки** — `BookmarkEntity` + `BookmarksScreen` + `BookmarksViewModel` + кнопка добавления в Player
- ✅ **Вернуться на 1 предложение** — `seekBackOneSentence()`
- ⬜ **Жесты навигации** — свайп влево/вправо для глав
- ⬜ **Двойное нажатие на экран** — ±30с
- ⬜ **Голосовое управление** — через Google Assistant

### 4.2 Прогресс и статистика

- ✅ **Чтение статистики** — `StatsTracker` + `ListeningSessionEntity`, `StatsDao`
- 🟡 **Оценка оставшегося времени** — data доступна, нет UI
- ⬜ **Экран статистики** — часы / дни / книги / скорость
- ⬜ **Ежедневная цель** — прогресс-кольцо
- ⬜ **Стрики (серии дней)**
- ⬜ **Виджет на рабочий стол**
- ⬜ **Уведомление-напоминание**
- ⬜ **Ачивки (gamification)**

---

## 5. БИБЛИОТЕКА И УПРАВЛЕНИЕ КНИГАМИ

### 5.1 Организация

- ✅ **Сортировка** — по lastOpenedAt DESC (дефолт), сортировка в `getAllBooks`
- ⬜ **Коллекции / полки**
- ⬜ **Теги**
- ⬜ **Фильтрация по формату / прогрессу**
- ⬜ **Поиск по библиотеке**
- ✅ **Обложки из книг** — извлекаются в FB2/EPUB, путь в `BookEntity.coverPath`
- ⬜ **Генерация обложек** для книг без неё (из названия)
- ⬜ **Редактирование метаданных**
- ⬜ **Подтверждение удаления**
- ⬜ **Множественный выбор**

### 5.2 Импорт

- ✅ **ACTION_OPEN_DOCUMENT** — системный пикер
- ✅ **takePersistableUriPermission** — долгосрочный доступ
- ✅ **Поддерживаемые форматы** — fb2, epub, txt, pdf
- ⬜ **Импорт из папки (сканирование)**
- ⬜ **Drag & Drop**
- ⬜ **Импорт по URL**
- ⬜ **Импорт из облака (Drive / Dropbox / OneDrive)**
- ⬜ **OPDS-каталоги**
- ⬜ **Прогресс импорта для больших файлов**
- 🟡 **Фоновый импорт** — парсинг запускается в `viewModelScope` (Dispatchers.IO), notification-прогресса нет
- ✅ **Экспорт библиотеки** — `LibraryExporter.kt` (CSV/JSON)

---

## 6. ИНТЕРФЕЙС И UX

### 6.1 Экран плеера

- ✅ **Обложка книги**
- ⬜ **Экран блокировки с анимацией (волна/пульс)**
- ✅ **Pitch/rate/volume в настройках голоса** + live resync
- 🟡 **Показ текста с подсветкой слова** — `currentWordStart/End` публикуются в state, UI отображения ещё нет
- ⬜ **Ночной режим экрана** — красные тона / минимальная яркость
- ⬜ **Крупный режим кнопок**
- ⬜ **«Now Playing» полноэкранный режим**
- ⬜ **Мини-плеер** плавающая панель на других экранах
- ⬜ **Анимация перехода между главами**

### 6.2 Общий UI

- ⬜ **Onboarding / wizard первого запуска**
- ✅ **Экран настроек приложения** — `SettingsScreen.kt`
- ✅ **Выбор темы** — light / dark / auto / amoled + dynamic color
- ⬜ **Выбор языка интерфейса** — ключ в DataStore есть, но локализация UI не применяется динамически
- 🟡 **Кастомизация accent-цвета** — dynamic color на Android 12+, кастомного выбора нет
- ✅ **AMOLED-тема** — чисто чёрный фон
- ⬜ **Landscape-режим**
- ⬜ **Tablet-адаптация (двухпанельный layout)**
- 🟡 **Accessibility** — `contentDescription` на иконках есть, полный audit не проводился
- ⬜ **App shortcuts** (long press на иконку)
- ⬜ **Haptic feedback на кнопках**

### 6.3 Уведомления

- ✅ **Кастомные vector-иконки** — `ic_notif_play/pause/next/prev/stop/small`
- ✅ **Прогресс в уведомлении** — `setSubText("NN%")`
- ✅ **Обложка в уведомлении** — `setLargeIcon` + `METADATA_KEY_ART` (с кэшированием bitmap)
- ✅ **Ongoing while bookId != null** — не смахивается, держит foreground
- ✅ **Play/Pause/Next/Prev/Stop actions** через `MediaButtonReceiver.buildMediaButtonPendingIntent`
- ✅ **Cancel button через MediaStyle** — тянет к `ACTION_STOP`
- ⬜ **Quick Settings Tile**

---

## 7. ГАРНИТУРА / MEDIA BUTTONS

*(эта секция — результат 6 раундов аудита; см. [DOCUMENTATION.md §9](DOCUMENTATION.md#9-приоритет-mediasession-и-маршрутизация-гарнитуры))*

- ✅ **MediaSessionCompat** с полным набором actions
- ✅ **Manual KeyEvent dispatch** в `onMediaButtonEvent` (обход OEM-глюков)
- ✅ **MediaButtonReceiver** с PendingIntent
- ✅ **android:appCategory="audio"** в манифесте
- ✅ **AudioAttributes USAGE_MEDIA** на TextToSpeech
- ✅ **Silent AudioTrack anchor** — наш процесс регистрируется как real audio producer
- ✅ **Hold audio focus через pause** — Spotify-style, не теряем headset priority
- ✅ **Ongoing notification пока книга загружена**
- ✅ **MEDIA_BUTTON intent-filter на сервисе** (для MediaButtonReceiver forward)
- ✅ **Reuse AudioFocusRequest** — нет утечки listener'ов
- ✅ **Auto-load last book** при headset Play на свежем сервисе
- ✅ **Re-assert isActive** в updateMediaSession
- ✅ **Auto-resume после AUDIOFOCUS_GAIN** (если pause был AUDIOFOCUS_LOSS_TRANSIENT)
- ✅ **ACTION_AUDIO_BECOMING_NOISY receiver** — pause при выдёргивании наушников

---

## 8. АВТОМОБИЛЬНЫЙ РЕЖИМ (Android Auto)

- ✅ **MediaBrowserServiceCompat** (`AutoMediaBrowserService.kt`) — browsable tree root → книги → главы
- ✅ **Разрешения** — `<service android:exported="true" android:name=".service.AutoMediaBrowserService">`
- ⬜ **automotive_app_desc.xml** — manifest для Android Auto
- ⬜ **Тестирование на DHU (Desktop Head Unit)**
- ⬜ **Упрощённый UI для авто**
- ⬜ **Голосовое управление через Assistant** — «Hey Google, play my book»
- ✅ **Auto-pause при звонке** — через AUDIOFOCUS_LOSS_TRANSIENT + auto-resume на GAIN

---

## 9. СИНХРОНИЗАЦИЯ И БЭКАП

- ✅ **Экспорт библиотеки в JSON/CSV** — `LibraryExporter.kt`
- ⬜ **Экспорт позиций чтения** — отдельно
- ⬜ **Импорт позиций**
- ⬜ **Google Drive бэкап**
- ⬜ **Синхронизация между устройствами**
- ⬜ **OPDS синк**

---

## 10. СОЦИАЛЬНЫЕ ФУНКЦИИ

- ⬜ **Выделить цитату и поделиться**
- ⬜ **Поделиться прогрессом** в соцсети
- ⬜ **Рекомендации похожих книг**
- ⬜ **Рейтинг книг** (5 звёзд)

---

## 11. ПРОИЗВОДИТЕЛЬНОСТЬ И СТАБИЛЬНОСТЬ

- ✅ **Watchdog** — 20-секундная защита от зависания TTS
- ✅ **Text processing на Dispatchers.Default** — main thread не блокируется
- ✅ **Bitmap cover caching** — `loadCoverBitmapCached`, один декод на книгу
- ✅ **TTS callback thread safety** — hop на Main через serviceScope.launch
- ✅ **currentLoadJob + currentSpeakJob tracking** — atomic cancel в pause
- ✅ **`ensureActive()` на каждой suspend-границе**
- ✅ **GlobalScope для critical persistence** (`savePosition`)
- ✅ **Stale callback guards** (`chapterIdx` + `isPlaying`)
- ✅ **`@Volatile` для межпоточных полей** (`lastTtsProgressAt`)
- ⬜ **Кэширование чанков** — prep next 5 chunks
- ⬜ **Retry при TTS onError** — переинициализация движка
- ⬜ **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS** — запрос + инструкция
- 🟡 **Memory management** — chapter text в БД, читаем по требованию; lazy-load по главам есть, но processedText рассчитывается каждый раз
- ⬜ **Crash reporting (Crashlytics)**
- ✅ **ANR prevention** — всё IO через IO dispatcher (audited 6 times)
- 🟡 **ProGuard rules** — базовые есть, detailed audit не проводился
- 🟡 **Unit-тесты** — парсеры покрыты (Fb2ParserTest, TxtParserTest); TtsEngine/SsmlBuilder тесты ещё нет
- ⬜ **UI-тесты** — Compose-тестов пока нет
- ✅ **CI/CD** — GitHub Actions (`.github/workflows/ci.yml`): test + assembleDebug + upload APK

---

## 12. МОНЕТИЗАЦИЯ (TBD)

- ⬜ **Модель не выбрана** — open source / donation / pro / ads?

---

## 13. ПУБЛИКАЦИЯ

- ⬜ **Иконка приложения** — профессиональный дизайн
- ⬜ **Splash screen** (Android 12+ API)
- ⬜ **Скриншоты для Google Play**
- ⬜ **Описание для Google Play** (RU/EN)
- ⬜ **Release APK signing** — keystore + signingConfig
- ⬜ **App Bundle (.aab)** для Google Play
- ⬜ **Версионирование (semver)**
- ✅ **Privacy Policy** — `PRIVACY_POLICY.md`
- ⬜ **Feature graphic 1024×500**

---

## 14. ЭКСПЕРИМЕНТАЛЬНЫЕ ИДЕИ

- ⬜ **AI-суммаризация** — краткое содержание главы через LLM API
- ⬜ **AI-генерация голоса** — Silero / Bark / XTTS (нейросетевой TTS)
- ⬜ **Wear OS компаньон**
- ⬜ **Home Assistant integration** — «Продолжить книгу на колонке»
- ⬜ **Multi-room через Chromecast**
- ⬜ **Скорость по пунктуации** — замедление на длинных предложениях
- ⬜ **Обучение произношению** — пользователь корректирует ударение, запоминаем
- ⬜ **Telegram-бот интеграция** — переслать боту → скачать
- ⬜ **Режим подкаста** — плейлист из нескольких книг
- ⬜ **Sleep stories** — встроенные короткие рассказы
- ⬜ **Фоновые звуки** — белый шум, дождь, камин поверх TTS
- ⬜ **Интеграция с Readwise / Kindle highlights**

---

## Приоритеты на ближайшие релизы

### v1.1 (стабилизация, UX) — следующий спринт

Высокий приоритет:
1. ⬜ **Quick Settings Tile** — play/pause в шторке
2. ⬜ **Виджет на рабочий стол** — текущая книга + контролы
3. ⬜ **UI для словаря произношений**
4. ⬜ **Экран статистики** прослушивания
5. ⬜ **Мини-плеер** на других экранах
6. ⬜ **Release APK + signing config + app bundle**

Средний:
7. ⬜ **Настраиваемая длительность fadeout** (UI)
8. ⬜ **Подтверждение удаления книги**
9. ⬜ **Подсветка читаемого слова в тексте**
10. ⬜ **Автогенерация обложек для книг без неё**

### v1.2 (организация библиотеки)
1. ⬜ Коллекции / полки / теги
2. ⬜ Поиск по библиотеке
3. ⬜ Импорт из папки + прогресс импорта
4. ⬜ Жесты навигации (свайп глав)
5. ⬜ Профили голоса привязанные к книге
6. ⬜ Ночной режим экрана плеера

### v2.0 (экосистема)
1. ⬜ Google Drive бэкап + синк между устройствами
2. ⬜ Android Auto full integration + тестирование
3. ⬜ AI-суммаризация
4. ⬜ Фоновые звуки + атмосферный режим
5. ⬜ Запись книги в MP3/OGG для офлайн
6. ⬜ OPDS-каталоги

### v2.1+ (экспериментальное)
1. ⬜ Нейросетевой TTS (Silero / Bark)
2. ⬜ Wear OS компаньон
3. ⬜ Мультиязычные книги с автопереключением голоса
4. ⬜ Голосовые шаблоны для персонажей

---

## Принципы разработки

После 6 раундов аудита Play/Pause сформировались следующие инварианты, которые **НЕ должны быть нарушены** при будущих изменениях:

1. **Все мутации shared state — только на Main thread** (TTS callbacks hop'ят через `serviceScope.launch`)
2. **Любой async-пайплайн воспроизведения трекается** в `currentLoadJob` или `currentSpeakJob`
3. **`pause()` атомарно отменяет оба job'а + сбрасывает transient-flag**
4. **`speakChapter` полагается на coroutine cancellation** как на единственный источник правды. Никакого `if (isPlaying) return` внутри корутины
5. **`ensureActive()`** — после каждой suspend-границы
6. **TTS callbacks** (`onUtteranceDone`, `onRangeStart`) проверяют `isPlaying` + `chapterIdx == currentChapterIndex`
7. **`onUtteranceError`** только логирует — не маршрутизируется в `onUtteranceDone`
8. **MediaSession priority** поддерживается 11 взаимозависимыми точками (см. `DOCUMENTATION.md §9`). Удаление любой ломает headset routing
9. **Audio focus не отпускается на паузе** — только на destroy (Spotify-style)
10. **Notification ongoing** пока загружена книга, не только при play
11. **Silent AudioTrack anchor** работает через pause/resume
12. **savePosition** через GlobalScope для критичной персистентности

---

> Документ обновлён. При существенных изменениях в архитектуре — отметить чекбоксы и обновить приоритеты.
