package com.github.vanspo.tus.store

import java.net.URL

class InMemoryTusUrlStore: TusUrlStore {
    private val map: MutableMap<String, URL> = mutableMapOf()
    override fun put(fingerprint: String, url: URL) {
        map[fingerprint] = url
    }

    override fun get(fingerprint: String): URL? {
        return map[fingerprint]
    }

    override fun remove(fingerprint: String) {
        map.remove(fingerprint)
    }
}
