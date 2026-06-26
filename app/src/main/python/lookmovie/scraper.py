from advanced_resolver import LookMovieResolver
import json

resolver = LookMovieResolver()

def get_stream(tmdb_id: int, content_type: str = "movie", season: int = 1, episode: int = 1):
    result = resolver.resolve(tmdb_id, content_type, season, episode)
    return json.dumps(result)
