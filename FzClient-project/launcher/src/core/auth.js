'use strict';
// Accounts: Microsoft (device code -> Xbox Live -> XSTS -> Minecraft) and offline (singleplayer only).
const crypto = require('crypto');
const { net } = require('electron');

/**
 * Azure app ("FzClient Launcher") client ID. Microsoft only lets an app sign in to Minecraft after Mojang has
 * approved it (https://aka.ms/mce-reviewappid). Put the approved ID here.
 */
const CLIENT_ID = process.env.FZ_MSA_CLIENT_ID || '3173158c-450f-4a3b-bb37-4ed7770c2e45';
const SCOPE = 'XboxLive.signin offline_access';

async function post(url, body, form) {
  const res = await net.fetch(url, {
    method: 'POST',
    headers: form ? { 'Content-Type': 'application/x-www-form-urlencoded' }
      : { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: form ? new URLSearchParams(body).toString() : JSON.stringify(body),
  });
  const text = await res.text();
  let json = {};
  try { json = JSON.parse(text); } catch { /* not json */ }
  return { ok: res.ok, status: res.status, json };
}

function msaAvailable() {
  return !!CLIENT_ID;
}

/** Step 1: asks Microsoft for a code the user types at microsoft.com/link. */
async function startDeviceCode() {
  if (!CLIENT_ID) throw new Error('Microsoft ログインはまだ使えません（アプリIDの承認待ち）');
  const r = await post('https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode',
    { client_id: CLIENT_ID, scope: SCOPE }, true);
  if (!r.ok) throw new Error(r.json.error_description || 'device code failed');
  return r.json; // { user_code, device_code, verification_uri, interval, expires_in, message }
}

/** Step 2: waits until the user has signed in, then finishes the Minecraft login. */
async function pollDeviceCode(dc, isCancelled) {
  const deadline = Date.now() + dc.expires_in * 1000;
  let interval = (dc.interval || 5) * 1000;
  while (Date.now() < deadline) {
    if (isCancelled && isCancelled()) throw new Error('キャンセルしました');
    await new Promise(r => setTimeout(r, interval));
    const r = await post('https://login.microsoftonline.com/consumers/oauth2/v2.0/token', {
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code', client_id: CLIENT_ID, device_code: dc.device_code,
    }, true);
    if (r.ok) return minecraftLogin(r.json);
    const err = r.json.error;
    if (err === 'authorization_pending') continue;
    if (err === 'slow_down') { interval += 5000; continue; }
    throw new Error(r.json.error_description || err || 'login failed');
  }
  throw new Error('時間切れです。もう一度ログインしてください');
}

async function refresh(account) {
  if (!account.refreshToken) throw new Error('no refresh token');
  const r = await post('https://login.microsoftonline.com/consumers/oauth2/v2.0/token', {
    grant_type: 'refresh_token', client_id: CLIENT_ID, refresh_token: account.refreshToken, scope: SCOPE,
  }, true);
  if (!r.ok) throw new Error('もう一度ログインしてください');
  return minecraftLogin(r.json);
}

async function minecraftLogin(msa) {
  const xbl = await post('https://user.auth.xboxlive.com/user/authenticate', {
    Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: 'd=' + msa.access_token },
    RelyingParty: 'http://auth.xboxlive.com', TokenType: 'JWT',
  });
  if (!xbl.ok) throw new Error('Xbox Live のログインに失敗しました');
  const uhs = xbl.json.DisplayClaims.xui[0].uhs;
  const xsts = await post('https://xsts.auth.xboxlive.com/xsts/authorize', {
    Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.json.Token] },
    RelyingParty: 'rp://api.minecraftservices.com/', TokenType: 'JWT',
  });
  if (!xsts.ok) {
    const code = xsts.json.XErr;
    if (code === 2148916233) throw new Error('この Microsoft アカウントには Xbox のプロフィールがありません');
    if (code === 2148916238) throw new Error('18歳未満のアカウントはファミリーに追加が必要です');
    throw new Error('XSTS のログインに失敗しました');
  }
  const mc = await post('https://api.minecraftservices.com/authentication/login_with_xbox', {
    identityToken: `XBL3.0 x=${uhs};${xsts.json.Token}`,
  });
  if (!mc.ok) throw new Error(mc.status === 403 ? 'このランチャーは Minecraft のログイン許可がまだありません' : 'Minecraft のログインに失敗しました');
  const res = await net.fetch('https://api.minecraftservices.com/minecraft/profile', {
    headers: { Authorization: 'Bearer ' + mc.json.access_token },
  });
  if (!res.ok) throw new Error('このアカウントは Minecraft Java 版を持っていません');
  const p = await res.json();
  return {
    type: 'msa', name: p.name, uuid: p.id, accessToken: mc.json.access_token, refreshToken: msa.refresh_token,
    expiresAt: Date.now() + (mc.json.expires_in || 86400) * 1000,
  };
}

/** Offline account (singleplayer / offline-mode servers only). Same UUID Minecraft itself would use. */
function offline(name) {
  const md5 = crypto.createHash('md5').update('OfflinePlayer:' + name).digest();
  md5[6] = (md5[6] & 0x0f) | 0x30;
  md5[8] = (md5[8] & 0x3f) | 0x80;
  const hex = md5.toString('hex');
  const uuid = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  return { type: 'offline', name, uuid, accessToken: '0' };
}

module.exports = { msaAvailable, startDeviceCode, pollDeviceCode, refresh, offline };
