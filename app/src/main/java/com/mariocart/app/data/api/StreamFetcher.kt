package com.mariocart.app.data.api

class StreamFetcher {
    fun getStreams(query: String): List<String> {
        // Delegate to LookMovieScraper
        val scraper = LookMovieScraper()
        return scraper.search(query)
    }
}