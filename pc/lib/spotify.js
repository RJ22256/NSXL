'use strict';
/*
 * Spotify Web API, signed in with your own Spotify app's Client ID (PKCE, no secret needed).
 * Set-up on developer.spotify.com: create an app, add the redirect URI below, copy its Client ID into NSXL.
 * Starting, skipping and changing playback through the API needs Spotify Premium; reading what's
 * playing and your playlists works on any account.
 */
const crypto = require('crypto');
const http = require('http');

const PORT = 8973;
const REDIRECT = 'http://127.0.0.1:' + PORT + '/callback';
const SCOPES = ['user-read-playback-state', 'user-modify-playback-state', 'user-read-currently-playing', 'playlist-read-private', 'playlist-read-collaborative', 'user-library-read'].join(' ');
const b64url = buf => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

class Spotify {
  /** store: { get(k, d), set(k, v) } (persisted); fetch: Electron net.fetch; open: opens a URL in the browser */
  constructor(store, fetch, open) {
    this.store = store; this.fetch = fetch; this.open = open; this.server = null;
  }
  get clientId() { return this.store.get('spotifyClientId', ''); }
  get connected() { return !!this.store.get('spotifyRefresh', null); }
  static get redirect() { return REDIRECT; }

  login() {
    if (!this.clientId) return Promise.reject(new Error('Add your Spotify Client ID first'));
    const verifier = b64url(crypto.randomBytes(48));
    const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
    const state = b64url(crypto.randomBytes(12));
    if (this.server) { try { this.server.close(); } catch (e) { } }
    return new Promise((resolve, reject) => {
      const done = (err, ok) => { setTimeout(() => { try { this.server.close(); } catch (e) { } this.server = null; }, 500); err ? reject(err) : resolve(ok); };
      this.server = http.createServer(async (req, res) => {
        const u = new URL(req.url, REDIRECT);
        if (u.pathname !== '/callback') { res.writeHead(404); res.end(); return; }
        const page = msg => { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); res.end(`<!doctype html><meta charset=utf-8><title>NSXL</title><body style="font:16px system-ui;background:#15151b;color:#eee;display:grid;place-items:center;height:90vh"><p>${msg}</p></body>`); };
        if (u.searchParams.get('state') !== state || !u.searchParams.get('code')) { page('Spotify sign-in didn\'t finish. You can close this tab and try again from NSXL.'); done(new Error(u.searchParams.get('error') || 'Sign-in cancelled')); return; }
        try {
          await this.token({ grant_type: 'authorization_code', code: u.searchParams.get('code'), redirect_uri: REDIRECT, code_verifier: verifier });
          page('Spotify is connected to NSXL. You can close this tab.');
          done(null, true);
        } catch (e) { page('Couldn\'t finish connecting: ' + String(e.message).replace(/</g, '&lt;')); done(e); }
      });
      this.server.on('error', e => reject(new Error('Port ' + PORT + ' is busy: ' + e.message)));
      this.server.listen(PORT, '127.0.0.1', () => {
        const q = new URLSearchParams({ client_id: this.clientId, response_type: 'code', redirect_uri: REDIRECT, scope: SCOPES, state, code_challenge_method: 'S256', code_challenge: challenge });
        this.open('https://accounts.spotify.com/authorize?' + q);
      });
      setTimeout(() => done(new Error('Sign-in timed out')), 5 * 60e3);
    });
  }

  logout() { this.store.set('spotifyRefresh', null); this.access = null; }

  async token(params) {
    const r = await this.fetch('https://accounts.spotify.com/api/token', {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: this.clientId, ...params }).toString(),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error_description || j.error || 'Spotify sign-in failed (' + r.status + ')');
    this.access = j.access_token; this.expires = Date.now() + (j.expires_in - 60) * 1000;
    if (j.refresh_token) this.store.set('spotifyRefresh', j.refresh_token);
    return j;
  }

  async api(method, path, body) {
    if (!this.connected) throw new Error('Connect Spotify in Settings › Music');
    if (!this.access || Date.now() > this.expires) await this.token({ grant_type: 'refresh_token', refresh_token: this.store.get('spotifyRefresh') });
    const r = await this.fetch('https://api.spotify.com/v1' + path, {
      method, headers: { Authorization: 'Bearer ' + this.access, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (r.status === 204) return null;
    const j = await r.json().catch(() => null);
    if (!r.ok) {
      const reason = j && j.error && (j.error.reason || j.error.message);
      if (r.status === 403 && /PREMIUM/i.test(String(reason))) throw new Error('Controlling playback needs Spotify Premium');
      if (r.status === 404 && /NO_ACTIVE_DEVICE/i.test(String(reason))) throw new Error('Open Spotify on a device first, then try again');
      throw new Error(reason || 'Spotify error ' + r.status);
    }
    return j;
  }

  async player() {
    const p = await this.api('GET', '/me/player?additional_types=episode');
    if (!p) return null;
    const it = p.item || {};
    return {
      playing: p.is_playing, shuffle: p.shuffle_state, repeat: p.repeat_state, progress: p.progress_ms, duration: it.duration_ms,
      title: it.name, artist: (it.artists || []).map(a => a.name).join(', ') || (it.show && it.show.name) || '',
      album: it.album ? it.album.name : '', art: ((it.album || it).images || [])[0] ? ((it.album || it).images[0].url) : null,
      device: p.device ? { id: p.device.id, name: p.device.name, type: p.device.type, volume: p.device.volume_percent } : null,
      context: p.context ? p.context.uri : null,
    };
  }
  async playlists() {
    const j = await this.api('GET', '/me/playlists?limit=50');
    return (j.items || []).filter(Boolean).map(p => ({ uri: p.uri, name: p.name, owner: p.owner && p.owner.display_name, art: (p.images || [])[0] ? p.images[0].url : null, tracks: p.tracks ? p.tracks.total : 0 }));
  }
  async devices() { const j = await this.api('GET', '/me/player/devices'); return (j.devices || []).map(d => ({ id: d.id, name: d.name, type: d.type, active: d.is_active, volume: d.volume_percent })); }
  play(context) { return this.api('PUT', '/me/player/play', context ? { context_uri: context } : undefined); }
  playLiked(uris) { return this.api('PUT', '/me/player/play', { uris }); }
  pause() { return this.api('PUT', '/me/player/pause'); }
  next() { return this.api('POST', '/me/player/next'); }
  prev() { return this.api('POST', '/me/player/previous'); }
  shuffle(on) { return this.api('PUT', '/me/player/shuffle?state=' + (on ? 'true' : 'false')); }
  repeat(mode) { return this.api('PUT', '/me/player/repeat?state=' + encodeURIComponent(mode)); }
  volume(v) { return this.api('PUT', '/me/player/volume?volume_percent=' + Math.max(0, Math.min(100, Math.round(v)))); }
  seek(ms) { return this.api('PUT', '/me/player/seek?position_ms=' + Math.max(0, Math.round(ms))); }
  transfer(id) { return this.api('PUT', '/me/player', { device_ids: [id], play: true }); }
  async liked() {
    const j = await this.api('GET', '/me/tracks?limit=50');
    return (j.items || []).map(i => i.track && i.track.uri).filter(Boolean);
  }
}
module.exports = Spotify;
