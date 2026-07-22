package com.ashtonhardy.piratesfilmcove.data.model

data class Genre(
    val id: String,
    val name: String,
    val type: String = "movie"  // "movie" or "tv"
)

val MOVIE_GENRES = listOf(
    Genre("", "All Movies"),
    Genre("28", "Action"),
    Genre("35", "Comedy"),
    Genre("27", "Horror"),
    Genre("878", "Sci-Fi"),
    Genre("18", "Drama"),
    Genre("10749", "Romance"),
    Genre("16", "Animation"),
    Genre("53", "Thriller"),
    Genre("12", "Adventure"),
    Genre("80", "Crime"),
    Genre("14", "Fantasy"),
    Genre("99", "Documentary"),
    Genre("10751", "Family"),
    Genre("9648", "Mystery"),
    Genre("37", "Western"),
    Genre("10752", "War"),
    Genre("36", "History"),
)

val TV_GENRES = listOf(
    Genre("10759", "Action & Adventure TV", "tv"),
    Genre("16", "Animation TV", "tv"),
    Genre("35", "Comedy TV", "tv"),
)
