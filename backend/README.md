# I-m-done Stream Resolver Backend

A FastAPI-based backend service that resolves video streams from 100+ embed servers for the I-m-done streaming application.

## Overview

This backend service replicates the stream extraction logic from the Android app, allowing it to be deployed as a remote service. Your Android app can call this API to get working video stream URLs instead of doing the extraction locally.

## Features

- **100+ Server Support**: VidLink, VidSrc.to, VidSrc.me, Videasy, AutoEmbed, Embed.su, and 94+ more
- **Parallel Probing**: Tests multiple servers simultaneously for faster resolution
- **Direct Stream Extraction**: Extracts HLS (.m3u8) and MP4 URLs with proper headers
- **Iframe Fallback**: Falls back to embed iframes if direct extraction fails
- **CORS Bypass**: Includes Referer and Origin headers to bypass CORS restrictions
- **TV Show Support**: Handles season and episode selection for TV series

## Installation

### Local Development

```bash
# Install dependencies
pip install -r requirements.txt

# Run the service
python stream_api_service.py

# The API will be available at http://localhost:8000
```

### Docker

```bash
# Build the image
docker build -t imdone-stream-resolver .

# Run the container
docker run -p 8000:8000 imdone-stream-resolver
```

### Deployment (Railway/Render)

```bash
# Deploy using Procfile
# Railway/Render will automatically use the Procfile to start the service
```

## API Endpoints

### Resolve Stream

**Endpoint**: `GET /api/stream`

**Parameters**:
- `tmdb_id` (int, required): TMDB movie or TV show ID
- `content_type` (string, optional): `"movie"` or `"tv"` (default: `"movie"`)
- `season` (int, optional): Season number for TV shows (default: 1)
- `episode` (int, optional): Episode number for TV shows (default: 1)

**Response**:
```json
{
  "success": true,
  "stream": {
    "url": "https://...",
    "type": "hls",
    "server": "VidLink",
    "headers": {
      "Referer": "https://vidlink.pro",
      "Origin": "https://vidlink.pro"
    }
  },
  "fallback_iframe": "https://..."
}
```

**Example**:
```bash
# Get a movie stream
curl "http://localhost:8000/api/stream?tmdb_id=550&content_type=movie"

# Get a TV show episode
curl "http://localhost:8000/api/stream?tmdb_id=1399&content_type=tv&season=1&episode=1"
```

### Get Available Servers

**Endpoint**: `GET /api/servers`

**Response**:
```json
{
  "servers": [
    {
      "name": "VidLink",
      "url": "https://vidlink.pro",
      "priority": 1
    },
    ...
  ]
}
```

## Integration with Android App

### Update Your App

1. **Add API Configuration**:
   ```kotlin
   const val STREAM_RESOLVER_API = "https://your-backend-url.com/api"
   ```

2. **Call the Backend**:
   ```kotlin
   val streamUrl = "https://your-backend-url.com/api/stream?tmdb_id=$tmdbId&content_type=$type&season=$season&episode=$episode"
   val response = httpClient.get(streamUrl)
   ```

3. **Parse Response**:
   ```kotlin
   val stream = response.body<StreamResponse>()
   val videoUrl = stream.stream.url
   val headers = stream.stream.headers
   ```

## Deployment Options

### Railway

1. Connect your GitHub repository
2. Railway will automatically detect the `Procfile`
3. Set environment variables if needed
4. Deploy with one click

### Render

1. Create a new Web Service
2. Connect your GitHub repository
3. Set the start command: `python stream_api_service.py`
4. Deploy

### Vercel

1. Create a new project
2. Connect your GitHub repository
3. Configure as a Python serverless function
4. Deploy

## Performance Considerations

- **Timeout**: Each server probe has a 5-second timeout
- **Parallel Probing**: Tests up to 10 servers simultaneously
- **Caching**: Consider caching results for 1-2 hours to reduce load
- **Rate Limiting**: Implement rate limiting to prevent abuse

## Troubleshooting

### Service Returns Empty Stream

- Some servers may be down or blocked
- Try a different TMDB ID
- Check if the content exists on TMDB

### CORS Errors

- The backend includes proper Referer and Origin headers
- If still getting CORS errors, the server may be blocking the origin

### Slow Response

- The service probes multiple servers in parallel
- First response typically takes 10-30 seconds
- Consider implementing caching on the client side

## Architecture

```
┌─────────────────┐
│  Android App    │
└────────┬────────┘
         │ HTTP Request
         ▼
┌─────────────────────────────────┐
│  Stream Resolver Backend        │
│  - Parallel Server Probing      │
│  - Stream Extraction            │
│  - Header Management            │
└────────┬────────────────────────┘
         │ HTTP Response
         ▼
┌──────────────────────────────────┐
│  100+ Streaming Servers          │
│  - VidLink, VidSrc, Videasy, etc │
└──────────────────────────────────┘
```

## License

MIT
