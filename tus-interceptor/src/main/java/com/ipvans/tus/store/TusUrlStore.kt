package com.ipvans.tus.store

import java.net.URL

interface TusUrlStore {
    fun put(fingerprint: String, url: URL)

    fun get(fingerprint: String): URL?

    fun remove(fingerprint: String)
}
