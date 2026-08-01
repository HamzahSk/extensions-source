plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MirrorInKomik"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "id"
        baseUrl = "https://mirrorinkomik.my.id"
        id = 527219732874523169L
    }
}
