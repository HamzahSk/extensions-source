plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Cgbum"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.2"

    source {
        lang = "id"
        baseUrl = "https://cgbum.com"
    }
}
