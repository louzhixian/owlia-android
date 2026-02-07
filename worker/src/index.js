export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/version') {
      return handleVersion(request, env, url);
    }

    return new Response('Not found', { status: 404 });
  },
};

async function handleVersion(request, env, url) {
  const clientVersion = url.searchParams.get('v') || 'unknown';
  const clientVersionCode = url.searchParams.get('vc') || '0';

  // Try KV cache first
  const cacheKey = 'latest_release';
  let release = null;

  if (env.CACHE) {
    const cached = await env.CACHE.get(cacheKey, 'json');
    if (cached) release = cached;
  }

  // Cache miss — fetch from GitHub
  if (!release) {
    try {
      const repo = env.GITHUB_REPO || 'zhixianio/botdrop-android';
      const res = await fetch(
        `https://api.github.com/repos/${repo}/releases/latest`,
        { headers: { 'User-Agent': 'botdrop-api-worker', Accept: 'application/vnd.github.v3+json' } },
      );

      if (!res.ok) {
        return jsonResponse({ error: 'upstream_error' }, 502);
      }

      const data = await res.json();
      const tagName = (data.tag_name || '').replace(/^v/, '');

      release = {
        latest_version: tagName,
        latest_version_code: extractVersionCode(tagName),
        download_url: data.html_url || '',
        release_notes: (data.body || '').slice(0, 500),
        min_supported: '0.1.0',
      };

      // Store in KV with TTL
      if (env.CACHE) {
        const ttl = parseInt(env.CACHE_TTL_SECONDS, 10) || 900;
        await env.CACHE.put(cacheKey, JSON.stringify(release), { expirationTtl: ttl });
      }
    } catch (err) {
      return jsonResponse({ error: 'fetch_failed' }, 502);
    }
  }

  // Log analytics data point
  if (env.ANALYTICS) {
    env.ANALYTICS.writeDataPoint({
      blobs: [clientVersion, request.cf?.country || 'XX'],
      doubles: [parseInt(clientVersionCode, 10) || 0],
      indexes: [clientVersion],
    });
  }

  return jsonResponse(release);
}

function extractVersionCode(semver) {
  const parts = semver.split('.');
  if (parts.length < 3) return 0;
  return parseInt(parts[0], 10) * 10000 + parseInt(parts[1], 10) * 100 + parseInt(parts[2], 10);
}

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': status === 200 ? 'public, max-age=300' : 'no-cache',
    },
  });
}
