# STRUKTUR REPOSITORI — Keiyoushi Extensions Source

> Dokumentasi teknis arsitektur repositori ekstensi Mihon/Tachiyomi (fork **Keiyoushi**).
> Panduan cepat (cheat sheet) bagi developer yang baru berkontribusi.

---

## 1. Struktur Folder & Proyek

### Pohon Direktori Root

```
extensions-source/
├── .github/                    # CI/CD, workflow, dan script pendukung
│   ├── workflows/              #   GitHub Actions workflows
│   ├── scripts/                #   Script Python (build matrix, merge repo, dsb.)
│   ├── always_build.json       #   Daftar ekstensi yang wajib selalu di-build
│   └── renovate.json           #   Konfigurasi Renovate bot (auto-update dependency)
├── common/                     # Aset bersama untuk semua ekstensi
│   ├── AndroidManifest.xml     #   Manifest dasar (dipakai ulang via sourceSets)
│   └── proguard-rules.pro      #   Aturan R8/proguard bersama
├── compiler/                   # KSP Symbol Processor (code-gen otomatis)
├── core/                       # Utilitas inti (network, utils, annotation, zip)
│   └── translations/           #   File i18n untuk preferensi ter-generate
├── gradle/                     # Konfigurasi build
│   ├── build-logic/            #   BuildSrc sebagai composite build (plugin kustom)
│   ├── libs.versions.toml      #   Version catalog (dependensi & plugin)
│   ├── kei.versions.toml       #   Version catalog plugin kustom "kei"
│   └── wrapper/                #   Gradle wrapper
├── lib/                        # Pustaka bantu (helper library) — 15 modul
├── lib-multisrc/               # "Theme" / template multi-source — 60+ modul
├── src/                        # Source code semua ekstensi (per bahasa)
│   ├── all/                    #   Ekstensi multi-bahasa / non-regional
│   ├── en/                     #   Ekstensi bahasa Inggris
│   ├── id/                     #   Ekstensi bahasa Indonesia (~91 ekstensi)
│   └── ko/                     #   Ekstensi bahasa Korea
├── build.gradle.kts            # Root build script
├── settings.gradle.kts         # Registrasi modul + plugin repository
├── gradle.properties           # Konfigurasi JVM / Gradle global
├── gradlew / gradlew.bat       # Gradle wrapper script
├── ktlintCodeStyle.xml         # Konfigurasi ktlint (IntelliJ)
├── .editorconfig               # Konfigurasi style editor (indent, dll.)
└── CONTRIBUTING.md             # Panduan kontribusi lengkap
```

### Pola Penamaan Source Code Ekstensi

Setiap ekstensi adalah **modul Gradle terpisah** yang terletak di `src/<lang>/<nama-ekstensi>/`:

```
src/id/komiku/
├── build.gradle.kts            # Deklarasi modul + konfigurasi keiyoushi
├── res/mipmap-*/               # Ikon aplikasi (wajib)
├── assets/                     # Opsional (mis. assets/i18n/*.properties)
├── AndroidManifest.xml         # Opsional (manifest tambahan per ekstensi)
├── proguard-rules.pro          # Opsional
└── src/eu/kanade/tachiyomi/extension/<lang>/<nama>/
    ├── Komiku.kt               # Class utama ekstensi (di-annotasi @Source)
    ├── Filters.kt              # Opsional — filter pencarian
    └── Dto.kt / *.kt           # Opsional — model data (DTO) & pembantu
```

Kode utama selalu berada pada paket `eu.kanade.tachiyomi.extension.<lang>.<name>` dan
di-compile ke APK dengan `applicationIdSuffix = "<lang>.<name>"`.

### Fungsi Folder Konfigurasi Penting

| Path | Fungsi |
|---|---|
| `gradle/libs.versions.toml` | Version catalog utama: versi AGP, Kotlin, KSP, OkHttp, Jsoup, dll. |
| `gradle/kei.versions.toml` | Versi plugin kustom `kei.*` + SDK (min 21, compile/target 34, Java 11). |
| `gradle/build-logic/` | Kumpulan plugin Gradle kustom (di-compile sebagai *included build*). |
| `common/AndroidManifest.xml` | Manifest dasar yang di-share semua modul ekstensi. |
| `core/` | Modul utilitas yang di-`implementation` oleh semua ekstensi & lib. |
| `compiler/` | KSP processor yang men-generate class `ExtensionGenerated` dari `@Source`. |
| `lib/` | Pustaka opsional; dipakai via `implementation(project(":lib:xxx"))`. |
| `lib-multisrc/` | Template sumber (theme) — basis class untuk banyak ekstensi sekaligus. |
| `.github/workflows/` | CI: build, lint, dan publish APK otomatis. |

---

## 2. Arsitektur & Anatomi Ekstensi

### Class Utama

Setiap ekstensi memiliki **tepat satu** class yang di-annotasi `@Source`
(`core/src/main/kotlin/keiyoushi/annotation/Source.kt`) dan mewarisi `HttpSource`
dari library `com.github.keiyoushi:extensions-lib`.

> **Catatan penting:** Di fork Keiyoushi ini, `extensions-lib` hanya menyediakan
> `HttpSource` — **`ParsedHttpSource` tidak tersedia**. Ekstensi harus mengimplementasikan
> method *Request* dan *Parse* secara eksplisit, atau mewarisi class theme dari
> `lib-multisrc/` (mis. `Madara`, `MangaThemesia`) yang sudah mengimplementasikannya.

```kotlin
@Source
abstract class Komiku : HttpSource() {
    override val supportsLatest = true
    override val baseUrl = "https://komiku.org"
    override val client = network.client.newBuilder().rateLimit(2).build()
    // ... method Request/Parse
}
```

`HttpSource` menyediakan properti & helper siap pakai:
- `network.client` — `OkHttpClient` default yang di-bootstrap oleh `NetworkHelper`.
- `headersBuilder()` / `headers` — `Headers` default (User-Agent, Accept-Language).
- `baseUrl`, `name`, `lang`, `id` — di-override otomatis oleh code-gen (lihat bawah).
- `getMangaUrl()` — menghasilkan URL detail dari `SManga`.
- `clientBuilder`, `network.await`/`awaitSuccess` untuk request async.

### Code Generation via KSP (`compiler/`)

KSP processor (`compiler/src/main/kotlin/keiyoushi/processor/SourceProcessor.kt`)
men-generate class `ExtensionGenerated` (bersifat `internal`):

| Skenario | Hasil generate |
|---|---|
| 1 blok `source {}` | Subclass dari class `@Source` + override `name`, `lang`, `id`, `baseUrl`. |
| Beberapa blok `source {}` | Class `SourceFactory` (implementasi `createSources()`) yang membuat satu instance per `source {}`. |
| `skipCodeGen = true` | `ExtensionGenerated` passthrough sederhana. |
| `baseUrl` tipe `mirrors` / `custom` | Menambahkan preferensi `MirrorPreferences` / `CustomUrlPreferences` + `setupPreferenceScreen()`. |

### Konfigurasi `keiyoushi {}` di `build.gradle.kts`

```kotlin
plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Komiku"                      // Nama tampilan sumber
    versionCode = 21                     // Harus dinaikkan setiap update
    contentWarning = ContentWarning.SAFE // SAFE | SUGGESTIVE | NSFW | MIXED | UNKNOWN
    libVersion = "1.4"                   // Versi extensions-lib yang valid (VALID_LIB_VERSIONS)
    theme = "madara"                     // Opsional — pakai theme dari lib-multisrc

    source {
        lang = "id"                      // Kode bahasa (id, en, ja, ko, ...)
        baseUrl = "https://komiku.org"   // URL utama
        // id = 3411809758861089969L      // Opsional — ID custom (default dihitung dari MD5 name/lang/versionId)
        // mirrors = listOf("...")        // Mode multi-URL (preferensi pemilihan)
        // versionId = 2                  // Opsional — untuk menambah versi source
    }

    deeplink {                           // Opsional — URL intent filter (deep link)
        host("mangadex.org")
        path("/title/..*")
    }
}
```

ID source default dihitung dari `MD5("${name.lowercase()}/$lang/$versionId")` (8 byte pertama),
sehingga **nama, bahasa, dan `versionId` tidak boleh berubah** setelah source dirilis.

### Metode Utama yang Wajib Diimplementasikan

| Kelompok | Metode Request | Metode Parse | Keterangan |
|---|---|---|---|
| Popular | `popularMangaRequest(page: Int): Request` | `popularMangaParse(response): MangasPage` | Daftar manga populer. |
| Latest | `latestUpdatesRequest(page)` | `latestUpdatesParse(response)` | Aktif jika `supportsLatest = true`. |
| Search | `searchMangaRequest(page, query, filters)` | `searchMangaParse(response)` | Pencarian + `getFilterList()` untuk filter. |
| Detail | `mangaDetailsRequest(manga)` | `mangaDetailsParse(response): SManga` | Isi `description`, `author`, `genre`, `status`, `thumbnail_url`. |
| Chapter | `chapterListRequest(manga)` | `chapterListParse(response): List<SChapter>` | Daftar chapter; gunakan `setUrlWithoutDomain()`. |
| Halaman | `pageListRequest(chapter)` | `pageListParse(response): List<Page>` | Gambar per chapter. |
| Gambar | `imageRequest(page): Request` | `imageUrlParse(response)` | Sering lempar `UnsupportedOperationException` bila `page.imageUrl` langsung dipakai. |

Pola umum `HttpSource` memisahkan pembuatan `Request` (Request method) dan penguraian
`Response` (Parse method). Helper yang sering dipakai:

```kotlin
val document = response.asJsoup()            // eu.kanade.tachiyomi.util.asJsoup
val data = response.parseAs<MyDto>()          // keiyoushi.utils.parseAs (kotlinx-serialization)
val date = "12/03/2024".tryParse(dateFormat)  // keiyoushi.utils.tryParse
```

### HTTP Client & Network Interceptor

Semua jaringan memakai **OkHttp 5.3.2** yang dibangun dari `network.client` bawaan `HttpSource`:

- **Interceptor kustom** — modifikasi header per request (mis. `Referer`, `Accept-Language`,
  `X-Requested-With`):
  ```kotlin
  override val client = network.client.newBuilder()
      .addInterceptor { chain ->
          val req = chain.request()
          chain.proceed(req.newBuilder().header("Referer", baseUrl).build())
      }
      .build()
  ```
- **Rate limit** (`core/.../network/RateLimit.kt`) — `OkHttpClient.Builder.rateLimit(permits, period, interval, shouldLimit)`
  menggunakan sliding-window + interceptor bertingkat. Contoh: `.rateLimit(2)`.
- **Cookie handling** — ekstensi yang butuh cookie memakai `lib:cookieinterceptor`
  (`keiyoushi.lib.cookieinterceptor.CookieInterceptor`).
- **Helper async** (`core/.../network/OkHttp.kt`) — `OkHttpClient.get/post/put/head(...)`
  sebagai extension `suspend`, mendukung context receiver `context(source: HttpSource)`,
  cache control default 10 menit, dan `ensureSuccess`.
- **Headers** — `headersBuilder()` untuk menambah header global; properti `headers`
  mengembalikan `Headers` final.

---

## 3. Sistem Build & Gradle

### Setup Multi-Module

- **Gradle multi-project**: setiap ekstensi adalah modul aplikasi Android tersendiri,
  ditambah modul `:core`, `:compiler`, `:lib:*`, dan `:lib-multisrc:*`.
- `settings.gradle.kts` me-load **semua** direktori di bawah `src/`, `lib/`, dan `lib-multisrc/`
  secara otomatis (`loadAllIndividualExtensions()`). Module path = `:src:<lang>:<name>`.
- **Composite build**: `gradle/build-logic` di-include sebagai `includedBuild`, berisi
  plugin Gradle kustom (lihat Bagian 4).
- **Configuration cache, build cache, dan parallel** diaktifkan via `gradle.properties`
  (`org.gradle.configuration-cache=true`, `org.gradle.caching=true`, `org.gradle.parallel=true`).
- Perintah `clean`, `spotlessApply`, `spotlessCheck` di root juga memanggil task yang sama
  pada `build-logic` (lihat `build.gradle.kts` root).

### Perintah Penting

| Perintah | Fungsi |
|---|---|
| `./gradlew src:id:komiku:assembleDebug` | Build satu ekstensi (APK debug). |
| `./gradlew :src:id:komiku:assembleRelease` | Build satu ekstensi (APK release, di-minify R8). |
| `./gradlew assembleDebug` | Build **semua** modul ekstensi (sangat lama). |
| `./gradlew spotlessApply` | Auto-format seluruh kode (ktlint + Google Java Format). |
| `./gradlew spotlessCheck` | Cek format tanpa mengubah file (dijalankan CI). |
| `./gradlew :core:testDebugUnitTest` | Jalankan unit test modul `:core` (Jsoup, JSON, dsb.). |
| `./gradlew testDebugUnitTest` | Jalankan unit test semua modul. |
| `./gradlew clean` | Bersihkan hasil build. |

> Tips: untuk pengembangan lokal, komentari `loadAllIndividualExtensions()` di
> `settings.gradle.kts` dan panggil `loadIndividualExtension("id", "komiku")` agar hanya
> modul tertentu yang di-load — build jauh lebih cepat.

APK hasil build berada di `src/<lang>/<name>/build/outputs/apk/{debug,release}/`.

---

## 4. Plugin, Dependensi & Tooling

### Plugin Gradle Kustom (dari `gradle/build-logic/`)

Semua plugin kustom ber-ID `kei.plugins.*` dan di-deklarasi sebagai alias di
`gradle/kei.versions.toml`:

| Plugin | Class Implementasi | Fungsi |
|---|---|---|
| `kei.plugins.android.base` | `PluginAndroidBase` | Config SDK (min 21 / compile 34 / target 34), kotlin config, auto-run spotless di `preBuild`. |
| `kei.plugins.extension` | `PluginExtension` | Plugin utama modul ekstensi: AGP application, KSP, code-gen, signing, versi, manifest. |
| `kei.plugins.library` | `PluginLibrary` | Untuk modul di `lib/`: AGP library + dependensi core. |
| `kei.plugins.multisrc` | `PluginMultiSrc` | Untuk theme di `lib-multisrc/`: validasi `libVersion`, `baseVersionCode`. |
| `kei.plugins.spotless` | `PluginSpotless` | Spotless (ktlint, Google Java Format) + aturan kustom `randomua-requires-getMangaUrl`. |

### Plugin Eksternal & Versi (dari `gradle/libs.versions.toml`)

| Plugin | Versi |
|---|---|
| Android Gradle Plugin (application & library) | 9.2.1 |
| Kotlin (JVM, serialization, samWithReceiver) | 2.3.21 |
| KSP | 2.3.9 |
| Spotless | 8.5.0 |
| ktlint (BOM) | 1.8.0 |
| TapMoc (tooling API) | 0.4.2 |

### Dependensi Utama (bundle `common`)

Dependensi inti dikelompokkan dalam bundle `common` dan di-`compileOnly` oleh modul
ekstensi (di-sedot dari aplikasi host saat runtime):

| Dependensi | Fungsi |
|---|---|
| `com.github.keiyoushi:extensions-lib` | **API inti ekstensi**: `HttpSource`, `Source`, `ConfigurableSource`, `SourceFactory`, model `SManga`/`SChapter`/`Page`, dll. |
| OkHttp 5.3.2 | HTTP client (synch/asynch, interceptor, WebSocket). |
| Jsoup 1.22.1 | Parsing HTML (`asJsoup`, CSS selector). |
| kotlinx-serialization-json / protobuf / okio | Parsing & serialisasi JSON/Protobuf. |
| kotlinx-coroutines (core & android) | Pemrograman asinkron. |
| injekt-core | Dependency injection ringan. |
| RxJava 1.3.8 | Kompatibilitas dengan API lama. |
| QuickJS | Runtime JavaScript untuk ekstensi (mis. eksekusi JS site). |
| jspecify | Anotasi nullability untuk kompatibilitas Kotlin 2.x. |
| JUnit 4.13.2 | Unit testing (`testImplementation`). |

### Pustaka `lib/` (15 modul)

| Modul | Fungsi |
|---|---|
| `cookieinterceptor` | Interceptor pengelola cookie. |
| `cryptoaes` | Enkripsi/deskripsi AES. |
| `dataimage` | Konversi gambar/data URI. |
| `e4p` | Helper Encode/Decode. |
| `i18n` | Internasionalisasi (file `messages_*.properties`). |
| `lzstring` | Kompresi LZ-String. |
| `publus` / `unpacker` / `synchrony` / `secretstream` / `seedrandom` / `speedbinb` | Helper deobfuscation & utilitas tertentu. |
| `randomua` | Generate User-Agent acak (wajib override `getMangaUrl()`). |
| `textinterceptor` | Interceptor pengubah body teks. |
| `zipinterceptor` | Interceptor unzip response. |

### Theme `lib-multisrc/` (60+ modul)

Template sumber siap pakai — contoh: `madara`, `mangathemesia`, `zmanga`, `zeistmanga`,
`mangareader`, `natsuid`, `oceanwp`, `wpcomics`, dll. Ekstensi cukup mewarisi class theme
dan me-*override* beberapa properti:

```kotlin
@Source
abstract class Manhwahana : Madara() {
    override val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale("id"))
    override val mangaSubString = "hana-komik"
    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val useNewChapterEndpoint = true
}
```

### CI/CD — GitHub Actions

| Workflow | Trigger | Fungsi |
|---|---|---|
| `build_pull_request.yml` | Pull Request | Build semua modul berubah dalam chunk (80 modul/job), lalu jalankan **extensions-inspector** untuk memvalidasi APK. |
| `build_dynamic.yml` | Push ke `main` | `spotlessApply` + `spotlessCheck`, lalu build & **tandatangani** ekstensi yang berubah (release) dengan keystore dari secrets. |
| `build_push.yml` | Push ke `main` | Deteksi modul berubah via `git diff` lalu build `assembleDebug`. |
| `opencode.yml`, `zizmor.yml`, `lock.yml`, `issue_moderator.yml`, `codeberg_mirror.yml` | Berbagai | Otomasi lain (AI review, security scan, lock thread, mirror repo). |

`generate-build-matrices.py` (`.github/scripts/`) menghitung modul yang perlu di-build
berdasarkan diff, termasuk dependensi transitif: perubahan di `lib/` → ikut membangun
`lib-multisrc/` yang bergantung → ikut membangun semua ekstensi yang memakai theme tsb.
Perubahan pada `core/`, `compiler/`, `common/`, atau `gradle/` memicu build **semua** modul.

---

## Referensi Cepat (Cheat Sheet)

1. **Membuat ekstensi baru**: buat folder `src/<lang>/<nama>` → tulis `build.gradle.kts`
   dengan blok `keiyoushi {}` → buat class `@Source abstract class X : HttpSource()`
   (atau turunkan theme) di `src/eu/kanade/tachiyomi/extension/<lang>/<nama>/`.
2. **Override method wajib**: `popularMangaRequest/Parse`, `latestUpdatesRequest/Parse`,
   `searchMangaRequest/Parse`, `mangaDetailsRequest/Parse`, `chapterListRequest/Parse`,
   `pageListRequest/Parse`, `imageRequest`, `getFilterList`.
3. **Update ekstensi**: naikkan `versionCode`, jangan ubah `name`/`lang`/`id`.
4. **Build**: `./gradlew src:id/<nama>:assembleDebug`
5. **Format**: `./gradlew spotlessApply` → `./gradlew spotlessCheck`
6. **Test**: `./gradlew :core:testDebugUnitTest`
