const net = require('net');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');

process.on('uncaughtException', (err) => {
  console.error('[Consola Error]', err.message);
});
process.on('unhandledRejection', (reason) => {
  console.error('[Consola Rejection]', reason);
});

const SERVER_DIR = __dirname;
const RCON_HOST = '127.0.0.1';
const RCON_PORT = 25575;
const RCON_PASS = 'xaviadmin';
const PANEL_PORT = 8090;

let currentMode = 'colegio'; // 'colegio', 'casa', 'ngrok'
let tunnelProcess = null;
let publicWebUrl = null;
let publicWsUrl = null;
let tunnelStatus = 'inactivo'; // 'inactivo', 'conectando', 'activo', 'error'
let adminChatHistory = [];

const WORKER_URL = 'https://server-cumbres.codenautajavier.workers.dev';
const WORKER_SECRET = 'xaviadmin123';
const PERMANENT_WEB_URL = 'https://server-cumbres.codenautajavier.workers.dev';
const PERMANENT_WS_URL = 'wss://server-cumbres.codenautajavier.workers.dev';
const NTFY_TOPIC = 'xavi_cumbres_mc_servidor_oficial';

async function publishToNtfyChannel(targetHost) {
  try {
    const cleanHost = targetHost.replace('https://', '').replace('wss://', '').replace('/', '').trim();
    const res = await fetch('https://ntfy.sh/' + NTFY_TOPIC, {
      method: 'POST',
      body: 'wss://' + cleanHost
    });
    if (res.ok) {
      console.log(' [AUTO-CONEXIÓN] Túnel publicado para el Lanzador Automático de amigos -> wss://' + cleanHost);
    }
  } catch (err) {
    console.error(' [AUTO-CONEXIÓN Error]', err.message);
  }
}

async function updateCloudflareWorker(targetHost) {
  try {
    const cleanHost = targetHost.replace('https://', '').replace('wss://', '').replace('/', '');
    const updateUrl = WORKER_URL + '/set-target?secret=' + WORKER_SECRET + '&target=' + cleanHost;
    const res = await fetch(updateUrl);
    const text = await res.text();
    console.log(' [WORKER] Dominio fijo sincronizado:', text);
  } catch (err) {
    console.error(' [WORKER Sincronización Error]', err.message);
  }
}

// Mantener el Worker caliente cada 30s y renovar el canal público cada 15 min
setInterval(() => {
  if (currentMode === 'casa' && publicWebUrl) {
    updateCloudflareWorker(publicWebUrl);
  }
}, 30000);

setInterval(() => {
  if (currentMode === 'casa' && publicWebUrl) {
    publishToNtfyChannel(publicWebUrl);
  }
}, 15 * 60 * 1000);

async function autoRefreshVoiceTurn() {
  const cfgPath = path.join(SERVER_DIR, 'voice_turn_config.json');
  const legacyPath = path.join(SERVER_DIR, 'metered_credentials.json');
  let cfg = null;
  if (fs.existsSync(cfgPath)) {
    try { cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8')); } catch (e) {}
  } else if (fs.existsSync(legacyPath)) {
    try { cfg = JSON.parse(fs.readFileSync(legacyPath, 'utf8')); cfg.provider = 'metered'; } catch (e) {}
  }
  if (!cfg) return;

  try {
    let noPasswd = [
      'stun:stun.cloudflare.com:3478',
      'stun:stun.nextcloud.com:443',
      'stun:stun.l.google.com:19302'
    ];
    let passwdEntries = [];
    let count = 1;

    if (cfg.provider === 'cloudflare' && cfg.keyId && cfg.apiToken) {
      const res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${cfg.keyId}/credentials/generate-ice-servers`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${cfg.apiToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ ttl: 86400 })
      });
      const data = await res.json();
      if (data && data.iceServers) {
        const srvList = Array.isArray(data.iceServers) ? data.iceServers : [data.iceServers];
        for (const srv of srvList) {
          const urls = Array.isArray(srv.urls) ? srv.urls : [srv.urls];
          for (const u of urls) {
            if (u.startsWith('stun:')) {
              if (!noPasswd.includes(u)) noPasswd.push(u);
            } else if (u.startsWith('turn:') || u.startsWith('turns:')) {
              passwdEntries.push(`  cf_turn_${count++}:\n    url: '${u}'\n    username: '${srv.username}'\n    password: '${srv.credential}'`);
            }
          }
        }
      }
    } else if (cfg.provider === 'metered' && cfg.appName && cfg.apiKey) {
      const res = await fetch(`https://${cfg.appName}.metered.live/api/v1/turn/credentials?apiKey=${cfg.apiKey}`);
      const data = await res.json();
      if (Array.isArray(data)) {
        for (const item of data) {
          const urls = Array.isArray(item.urls) ? item.urls : [item.urls];
          for (const u of urls) {
            if (u.startsWith('stun:')) {
              if (!noPasswd.includes(u)) noPasswd.push(u);
            } else if (u.startsWith('turn:')) {
              passwdEntries.push(`  metered_${count++}:\n    url: '${u}'\n    username: '${item.username}'\n    password: '${item.credential}'`);
            }
          }
        }
      }
    }

    if (passwdEntries.length > 0) {
      let yaml = 'voice_servers_no_passwd:\n';
      for (const s of noPasswd) yaml += `- '${s}'\n`;
      yaml += 'voice_servers_passwd:\n' + passwdEntries.join('\n') + '\n';
      const icePath = path.join(SERVER_DIR, 'bungee', 'plugins', 'EaglercraftXBungee', 'ice_servers.yml');
      fs.writeFileSync(icePath, yaml, 'utf8');
      console.log(' [VOZ TURN] Servidores TURN sincronizados y actualizados con exito (' + cfg.provider + ').');
    }
  } catch (err) {
    console.error(' [VOZ TURN Error]', err.message);
  }
}
autoRefreshVoiceTurn();
setInterval(autoRefreshVoiceTurn, 12 * 60 * 60 * 1000);

function copyToClipboard(text) {
  try {
    const cp = spawn('clip', [], { stdio: ['pipe', 'ignore', 'ignore'] });
    cp.stdin.write(text);
    cp.stdin.end();
  } catch (e) {}
}

function startCloudflareTunnel() {
  const cfExe = path.join(SERVER_DIR, 'cloudflared.exe');
  if (!fs.existsSync(cfExe)) {
    console.error(' [TUNEL] Error: No se encontró cloudflared.exe en ' + cfExe);
    tunnelStatus = 'error';
    return;
  }
  stopTunnel();
  currentMode = 'casa';
  tunnelStatus = 'conectando';
  console.log(' [TUNEL] Iniciando Cloudflare Quick Tunnel en puerto 8081...');

  try {
    tunnelProcess = spawn(cfExe, ['tunnel', '--url', 'http://localhost:8081'], {
      windowsHide: true
    });

    const handleCfData = (data) => {
      const text = data.toString();
      const match = text.match(/https:\/\/([a-zA-Z0-9-]+\.trycloudflare\.com)/);
      if (match && !publicWebUrl) {
        publicWebUrl = match[0];
        publicWsUrl = 'wss://' + match[1];
        tunnelStatus = 'activo';
        copyToClipboard(publicWsUrl);
        publishToNtfyChannel(match[1]);
        updateCloudflareWorker(match[1]);
        try {
          fs.writeFileSync(path.join(SERVER_DIR, 'ENLACE_ACTUAL.txt'),
            '=== SERVIDOR EAGLERCRAFT DE XAVI (MODO CASA - CLOUDFLARE) ===\r\n\r\n' +
            '🚀 1. ARCHIVO / LANZADOR AUTOMÁTICO PARA AMIGOS (Nunca cambia y salta la VPN):\r\n' +
            '   Tus amigos solo tienen que abrir "Entrar_Servidor_Xavi.html" o usar el\r\n' +
            '   Eaglercraft con Auto-Conexión poniendo como servidor: wss://xavi\r\n\r\n' +
            '🌐 2. Túnel Directo de Hoy (100% compatible con la VPN del colegio):\r\n' +
            '   Web: ' + publicWebUrl + '\r\n' +
            '   WS:  ' + publicWsUrl + '\r\n\r\n' +
            '🛡️ 3. PANEL DE CONTROL DE XAVI:\r\n' +
            '   http://localhost:' + PANEL_PORT + '\r\n\r\n' +
            'Generado: ' + new Date().toLocaleString() + '\r\n', 'utf8');
        } catch (e) {}
        printCasaBanner();
      }
    };

    tunnelProcess.stdout.on('data', handleCfData);
    tunnelProcess.stderr.on('data', handleCfData);

    tunnelProcess.on('close', (code) => {
      console.log(' [TUNEL] Proceso de túnel Cloudflare finalizado (code ' + code + ')');
      if (tunnelStatus === 'activo') tunnelStatus = 'inactivo';
      tunnelProcess = null;
    });
  } catch (e) {
    console.error(' [TUNEL Error]', e.message);
    tunnelStatus = 'error';
  }
}

function startNgrokTunnel() {
  stopTunnel();
  currentMode = 'ngrok';
  tunnelStatus = 'conectando';
  console.log(' [TUNEL] Iniciando Ngrok Tunnel en puerto 8081...');
  try {
    tunnelProcess = spawn('ngrok', ['http', '8081', '--log=stdout'], {
      windowsHide: true
    });

    const handleNgrokData = (data) => {
      const text = data.toString();
      const match = text.match(/url=(https:\/\/[a-zA-Z0-9-]+\.ngrok-free\.app)/) || text.match(/(https:\/\/[a-zA-Z0-9-]+\.ngrok-free\.app)/);
      if (match && !publicWebUrl) {
        publicWebUrl = match[1];
        publicWsUrl = publicWebUrl.replace('https://', 'wss://');
        tunnelStatus = 'activo';
        copyToClipboard(publicWsUrl);
        try {
          fs.writeFileSync(path.join(SERVER_DIR, 'ENLACE_ACTUAL.txt'),
            '=== SERVIDOR EAGLERCRAFT DE XAVI (MODO CASA - NGROK) ===\r\n\r\n' +
            '🌐 1. ENLACE WEB:\r\n' +
            publicWebUrl + '\r\n\r\n' +
            '🎮 2. DIRECCIÓN SERVIDOR:\r\n' +
            publicWsUrl + '\r\n\r\n' +
            '🛡️ 3. PANEL DE CONTROL DE XAVI:\r\n' +
            'http://localhost:' + PANEL_PORT + '\r\n\r\n' +
            'Generado: ' + new Date().toLocaleString() + '\r\n', 'utf8');
        } catch (e) {}
        printNgrokBanner();
      }
    };

    tunnelProcess.stdout.on('data', handleNgrokData);
    tunnelProcess.stderr.on('data', handleNgrokData);

    tunnelProcess.on('close', (code) => {
      console.log(' [TUNEL] Proceso de Ngrok finalizado (code ' + code + ')');
      if (tunnelStatus === 'activo') tunnelStatus = 'inactivo';
      tunnelProcess = null;
    });
  } catch (e) {
    console.error(' [TUNEL Error]', e.message);
    tunnelStatus = 'error';
  }
}

function stopTunnel() {
  if (tunnelProcess) {
    try { tunnelProcess.kill(); } catch (e) {}
    tunnelProcess = null;
  }
  try { execSync('taskkill /f /im cloudflared.exe', { stdio: 'ignore' }); } catch (e) {}
  try { execSync('taskkill /f /im ngrok.exe', { stdio: 'ignore' }); } catch (e) {}
  publicWebUrl = null;
  publicWsUrl = null;
  tunnelStatus = 'inactivo';
  try {
    fs.writeFileSync(path.join(SERVER_DIR, 'ENLACE_ACTUAL.txt'), 'Servidor apagado.\r\n', 'utf8');
  } catch (e) {}
}

let globalRl = null;

function printCasaBanner() {
  console.log('\n========================================================================');
  console.log('  🚀 SERVIDOR EAGLERCRAFT DE XAVI EN [MODO CASA] - ¡ONLINE!');
  console.log('========================================================================');
  console.log('  ✅ ¡AUTO-CONECTOR SINCRONIZADO! Tus amigos NO necesitan cambiar nada.');
  console.log('     • Con su Lanzador "Entrar_Servidor_Xavi.html" entran en 1 clic.');
  console.log('     • Con el Eaglercraft Auto-Conexión usan siempre: wss://xavi');
  console.log('');
  console.log('  🌐 Túnel Cloudflare de Hoy (Copiado al portapapeles - Salta la VPN):');
  console.log('     🎮 Minecraft: ' + publicWsUrl);
  console.log('     🌐 Web:       ' + publicWebUrl);
  console.log('');
  console.log('  🛡️ Panel de Control de Administrador de Xavi:');
  console.log('     👉 http://localhost:' + PANEL_PORT);
  console.log('========================================================================\n');
  if (globalRl) {
    try { globalRl.prompt(true); } catch (e) {}
  }
}

function printNgrokBanner() {
  console.log('\n========================================================================');
  console.log('  🚀 SERVIDOR EAGLERCRAFT DE XAVI EN [MODO CASA - NGROK] - ¡ONLINE!');
  console.log('========================================================================');
  console.log('  📋 ¡DIRECCIÓN COPIADA AL PORTAPAPELES (LISTA PARA PEGAR EN WHATSAPP)!');
  console.log('');
  console.log('  🌐 1. Enlace Web (Deben dar clic a Visit Site una vez):');
  console.log('     👉 ' + publicWebUrl);
  console.log('');
  console.log('  🎮 2. Dirección para añadir a Eaglercraft:');
  console.log('     👉 ' + publicWsUrl);
  console.log('========================================================================\n');
}

function sendRconCommand(command, timeoutMs = 6000) {
  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    let authenticated = false;
    let responseText = '';
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('RCON timeout'));
    }, timeoutMs);

    function makePacket(reqId, type, payload) {
      const payloadBuf = Buffer.from(payload, 'utf8');
      const length = 4 + 4 + payloadBuf.length + 2;
      const buf = Buffer.alloc(4 + length);
      buf.writeInt32LE(length, 0);
      buf.writeInt32LE(reqId, 4);
      buf.writeInt32LE(type, 8);
      payloadBuf.copy(buf, 12);
      buf.writeInt16LE(0, 12 + payloadBuf.length);
      return buf;
    }

    socket.connect(RCON_PORT, RCON_HOST, () => {
      socket.write(makePacket(1, 3, RCON_PASS));
    });

    socket.on('data', (data) => {
      if (data.length < 12) return;
      const reqId = data.readInt32LE(4);
      const type = data.readInt32LE(8);
      const body = data.subarray(12, data.length - 2).toString('utf8');

      if (!authenticated) {
        if (reqId === -1) {
          clearTimeout(timer);
          socket.destroy();
          reject(new Error('Password RCON incorrecta'));
          return;
        }
        authenticated = true;
        socket.write(makePacket(2, 2, command));
      } else if (reqId === 2) {
        responseText += body;
        clearTimeout(timer);
        socket.end();
        resolve(responseText.replace(/\u00a7[0-9a-fk-or]/gi, '').trim());
      }
    });

    socket.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

async function safeShutdown() {
  console.log('====================================================');
  console.log(' [AUTOGUARDADO] Guardando mundo e inventarios...');
  console.log('====================================================');
  try {
    const res1 = await sendRconCommand('guardar');
    console.log(' ->', res1 || 'Guardado por plugin completado.');
    const res2 = await sendRconCommand('save-all');
    console.log(' ->', res2 || 'save-all ejecutado.');
    await new Promise(r => setTimeout(r, 1200));
    console.log(' [APAGANDO] Enviando orden de apagado limpio (stop)...');
    await sendRconCommand('stop').catch(() => {});
    for (let i = 0; i < 12; i++) {
      await new Promise(r => setTimeout(r, 500));
      try {
        await sendRconCommand('list', 400);
      } catch (e) {
        console.log(' -> Servidor Minecraft guardado y cerrado limpiamente.');
        break;
      }
    }
  } catch (e) {
    console.log(' -> El servidor ya estaba apagado o cerrándose.');
  }

  try {
    stopTunnel();
  } catch (e) {}
  try {
    execSync('taskkill /f /im java.exe', { stdio: 'ignore' });
  } catch (e) {}
  try {
    execSync('powershell.exe -ExecutionPolicy Bypass -Command "$ports = @(8081, 25565, 25575, 25577); $procs = Get-NetTCPConnection -LocalPort $ports -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 4 }; if ($procs) { $procs | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }"', { stdio: 'ignore' });
  } catch (e) {}
  console.log(' [OK] Todos los servicios se han detenido de forma segura.');
}

function parseConfigYaml(yml) {
  const getBool = (key, def) => {
    const m = yml.match(new RegExp('^' + key + ':\\s*(true|false)', 'm'));
    return m ? m[1] === 'true' : def;
  };
  const getFriends = () => {
    const inline = yml.match(/trusted-friends:\s*\[(.*?)\]/);
    if (inline) {
      return inline[1].split(',').map(s => s.trim().replace(/['"]/g, '')).filter(Boolean);
    }
    const lines = yml.split(/\r?\n/);
    const friends = [];
    let inFriends = false;
    for (const l of lines) {
      if (l.match(/^trusted-friends:/)) { inFriends = true; continue; }
      if (inFriends) {
        const item = l.match(/^\s*-\s*(.+)/);
        if (item) {
          friends.push(item[1].trim().replace(/['"]/g, ''));
        } else if (l.match(/^[a-zA-Z0-9_-]+:/)) {
          break;
        }
      }
    }
    return friends;
  };
  const getZone = () => {
    const x = yml.match(/x:\s*(-?\d+)/);
    const y = yml.match(/y:\s*(-?\d+)/);
    const z = yml.match(/z:\s*(-?\d+)/);
    const r = yml.match(/radius:\s*(\d+)/);
    return {
      x: x ? parseInt(x[1], 10) : -276,
      y: y ? parseInt(y[1], 10) : 67,
      z: z ? parseInt(z[1], 10) : -10,
      radius: r ? parseInt(r[1], 10) : 32
    };
  };

  return {
    houseProtection: getBool('house-protection-enabled', true),
    autoBan: getBool('auto-ban-on-grief', true),
    protectChests: getBool('protect-house-chests', true),
    antiExplosion: getBool('anti-explosion-destroy', true),
    antiFire: getBool('anti-fire-spread', true),
    keepInventory: getBool('keep-inventory', true),
    globalBuildLock: getBool('global-build-lock', false),
    friends: getFriends(),
    zone: getZone()
  };
}

function parseWarningsYaml(text) {
  const warnings = {};
  if (!text) return warnings;
  const lines = text.split(/\r?\n/);
  let currentTarget = null;
  for (const line of lines) {
    const playerMatch = line.match(/^\s{2}([a-zA-Z0-9_]+):/);
    if (playerMatch) {
      currentTarget = playerMatch[1].toLowerCase();
      warnings[currentTarget] = [];
      continue;
    }
    const itemMatch = line.match(/^\s*-\s*['"]?(.*?)['"]?$/);
    if (itemMatch && currentTarget) {
      warnings[currentTarget].push(itemMatch[1]);
    }
  }
  return warnings;
}


function parseAlliancesYaml(text) {
  const alliances = {};
  if (!text) return alliances;
  const lines = text.split(/\r?\n/);
  let cur = null;
  for (const line of lines) {
    const mAl = line.match(/^\s{2}([a-zA-Z0-9_]+):/);
    if (mAl) {
      cur = { name: mAl[1], leader: '', members: [], friendlyFire: false, kills: 0, warsWon: 0, enemies: [] };
      alliances[mAl[1].toLowerCase()] = cur;
      continue;
    }
    if (!cur) continue;
    const mName = line.match(/^\s{4}name:\s*['"]?(.*?)['"]?$/);
    if (mName) { cur.name = mName[1]; continue; }
    const mLeader = line.match(/^\s{4}leader:\s*['"]?(.*?)['"]?$/);
    if (mLeader) { cur.leader = mLeader[1]; continue; }
    const mFF = line.match(/^\s{4}friendlyFire:\s*(true|false)/i);
    if (mFF) { cur.friendlyFire = mFF[1].toLowerCase() === 'true'; continue; }
    const mKills = line.match(/^\s{4}kills:\s*(\d+)/);
    if (mKills) { cur.kills = parseInt(mKills[1], 10); continue; }
    const mWars = line.match(/^\s{4}warsWon:\s*(\d+)/);
    if (mWars) { cur.warsWon = parseInt(mWars[1], 10); continue; }
    const mItem = line.match(/^\s*-\s*['"]?(.*?)['"]?$/);
    if (mItem) {
      if (cur._section === 'enemies') cur.enemies.push(mItem[1]);
      else cur.members.push(mItem[1]);
      continue;
    }
    if (line.match(/^\s{4}members:/)) cur._section = 'members';
    if (line.match(/^\s{4}enemies:/)) cur._section = 'enemies';
  }
  return alliances;
}



function parseHonorYaml(text) {
  const honor = {};
  if (!text) return honor;
  const lines = text.split(/\r?\n/);
  for (const line of lines) {
    const m = line.match(/^\s{2}([a-zA-Z0-9_]+):\s*(-?\d+)/);
    if (m) {
      honor[m[1].toLowerCase()] = parseInt(m[2], 10);
    }
  }
  return honor;
}

function parseChestsYaml(text) {
  const chests = [];
  if (!text) return chests;
  const lines = text.split(/\r?\n/);
  let cur = null;
  for (const line of lines) {
    const mHead = line.match(/^\s{2}([a-zA-Z0-9_\-]+):/);
    if (mHead) {
      cur = { id: mHead[1], world: 'world', x: 0, y: 0, z: 0, owner: '', claimedDate: '' };
      chests.push(cur);
      continue;
    }
    if (!cur) continue;
    const mWorld = line.match(/^\s{4}world:\s*['"]?(.*?)['"]?$/);
    if (mWorld) { cur.world = mWorld[1]; continue; }
    const mX = line.match(/^\s{4}x:\s*(-?\d+)/);
    if (mX) { cur.x = parseInt(mX[1], 10); continue; }
    const mY = line.match(/^\s{4}y:\s*(-?\d+)/);
    if (mY) { cur.y = parseInt(mY[1], 10); continue; }
    const mZ = line.match(/^\s{4}z:\s*(-?\d+)/);
    if (mZ) { cur.z = parseInt(mZ[1], 10); continue; }
    const mOwner = line.match(/^\s{4}owner:\s*['"]?(.*?)['"]?$/);
    if (mOwner) { cur.owner = mOwner[1]; continue; }
    const mDate = line.match(/^\s{4}claimedDate:\s*['"]?(.*?)['"]?$/);
    if (mDate) { cur.claimedDate = mDate[1]; continue; }
  }
  return chests;
}

function getServerState() {
  const bannedPath = path.join(SERVER_DIR, 'server', 'banned-players.json');
  const usercachePath = path.join(SERVER_DIR, 'server', 'usercache.json');
  const logPath = path.join(SERVER_DIR, 'server', 'logs', 'latest.log');
  const cfgPath = path.join(SERVER_DIR, 'server', 'plugins', 'XaviAdminProtect', 'config.yml');
  const warnsPath = path.join(SERVER_DIR, 'server', 'plugins', 'XaviAdminProtect', 'warnings.yml');
  const alliancesPath = path.join(SERVER_DIR, 'server', 'plugins', 'XaviAdminProtect', 'alliances.yml');
  const honorPath = path.join(SERVER_DIR, 'server', 'plugins', 'XaviAdminProtect', 'honor.yml');
  const chestsPath = path.join(SERVER_DIR, 'server', 'plugins', 'XaviAdminProtect', 'chests.yml');

  let banned = [];
  try {
    if (fs.existsSync(bannedPath)) {
      banned = JSON.parse(fs.readFileSync(bannedPath, 'utf8'));
    }
  } catch (e) {}

  let warnings = {};
  try {
    if (fs.existsSync(warnsPath)) {
      warnings = parseWarningsYaml(fs.readFileSync(warnsPath, 'utf8'));
    }
  } catch (e) {}

  let knownPlayers = [];
  try {
    if (fs.existsSync(usercachePath)) {
      knownPlayers = JSON.parse(fs.readFileSync(usercachePath, 'utf8')).map(u => u.name)
        .filter(n => n && n !== 'tu_nombre_de_jugador');
    }
  } catch (e) {}

  let logs = '';
  let chatMessages = [];
  try {
    if (fs.existsSync(logPath)) {
      const allLines = fs.readFileSync(logPath, 'utf8').split(/\r?\n/);
      
      // Filter out RCON polling noise for the clean console view
      const cleanLines = allLines.filter(l => !l.includes('Rcon connection from:') && !l.includes('RCON Listener'));
      logs = cleanLines.slice(-70).join('\n').replace(/\x1B\[[0-9;]*[a-zA-Z]/g, '');

      // Parse real chat from last 1500 lines stripping ANSI codes
      const chatScan = allLines.slice(-1500);
      for (let rawLine of chatScan) {
        const line = rawLine.replace(/\x1B\[[0-9;]*[a-zA-Z]/g, '');
        if (line.includes('Async Chat Thread')) {
          const timeMatch = line.match(/^\[(\d{2}:\d{2}:\d{2})\]/);
          const time = timeMatch ? timeMatch[1] : '';
          const after = line.split(/\[Async Chat Thread.*?\]:\s+/)[1];
          if (after) {
            const m = after.match(/^(?:\[[^\]]*\]\s*)?(?:<([^>]+)>|([a-zA-Z0-9_]{2,16})):\s+(.*)/);
            if (m) {
              chatMessages.push({
                time,
                user: (m[1] || m[2]).trim(),
                text: m[3].trim(),
                type: 'chat'
              });
            }
          }
        } else if (line.includes('joined the game') || line.includes('left the game')) {
          const timeMatch = line.match(/^\[(\d{2}:\d{2}:\d{2})\]/);
          const time = timeMatch ? timeMatch[1] : '';
          const jm = line.match(/:\s+([a-zA-Z0-9_]{2,16})\s+(joined the game|left the game)/);
          if (jm) {
            chatMessages.push({
              time,
              user: jm[1],
              text: jm[2].includes('joined') ? 'ha entrado al juego' : 'ha salido del juego',
              type: jm[2].includes('joined') ? 'join' : 'leave'
            });
          }
        }
      }
    }
  } catch (e) {}

  // Combine log chat with messages sent from the admin web panel
  if (adminChatHistory && adminChatHistory.length > 0) {
    chatMessages = chatMessages.concat(adminChatHistory);
    chatMessages.sort((a, b) => (a.time || '').localeCompare(b.time || ''));
  }
  if (chatMessages.length > 80) chatMessages = chatMessages.slice(-80);

  let rules = {
    houseProtection: true,
    autoBan: true,
    protectChests: true,
    antiExplosion: true,
    antiFire: true,
    keepInventory: true,
    globalBuildLock: false,
    friends: [],
    zone: { x: -276, y: 67, z: -10, radius: 32 }
  };
  try {
    if (fs.existsSync(cfgPath)) {
      const cfgText = fs.readFileSync(cfgPath, 'utf8');
      rules = parseConfigYaml(cfgText);
    }
  } catch (e) {}

  let alliances = {};
  try { if (fs.existsSync(alliancesPath)) alliances = parseAlliancesYaml(fs.readFileSync(alliancesPath, 'utf8')); } catch (e) {}

  
  let honor = {};
  try { if (fs.existsSync(honorPath)) honor = parseHonorYaml(fs.readFileSync(honorPath, 'utf8')); } catch (e) {}

  let chests = [];
  try { if (fs.existsSync(chestsPath)) chests = parseChestsYaml(fs.readFileSync(chestsPath, 'utf8')); } catch (e) {}

  return { banned, knownPlayers, logs, chatMessages, rules, warnings, alliances, honor, chests };
}

const HTML_PAGE = `<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Consola de Control Total — Servidor de Xavi</title>
<style>
  :root {
    --bg: #090d16;
    --card: #131b2e;
    --card-hover: #19233c;
    --border: #23314e;
    --border-light: #334468;
    --accent: #3b82f6;
    --accent-glow: rgba(59, 130, 246, 0.25);
    --green: #10b981;
    --green-bg: rgba(16, 185, 129, 0.15);
    --red: #f43f5e;
    --red-bg: rgba(244, 63, 94, 0.15);
    --amber: #f59e0b;
    --amber-bg: rgba(245, 158, 11, 0.15);
    --purple: #8b5cf6;
    --purple-bg: rgba(139, 92, 246, 0.15);
    --text: #f8fafc;
    --muted: #94a3b8;
  }
  * { box-sizing: border-box; font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; }
  body { margin: 0; padding: 20px; background: var(--bg); color: var(--text); }
  
  /* HEADER */
  .header {
    display: flex; justify-content: space-between; align-items: center; flex-wrap: gap;
    background: linear-gradient(135deg, #131b2e 0%, #0d1322 100%);
    padding: 20px 24px; border-radius: 16px; border: 1px solid var(--border);
    margin-bottom: 20px; box-shadow: 0 8px 24px rgba(0,0,0,0.3);
  }
  .header-info h1 { margin: 0; font-size: 23px; display: flex; align-items: center; gap: 10px; font-weight: 800; letter-spacing: -0.5px; }
  .header-subtitle { color: var(--muted); font-size: 13px; margin-top: 6px; display: flex; gap: 16px; flex-wrap: wrap; }
  .ip-chip {
    background: #090d16; border: 1px solid var(--border); padding: 4px 10px;
    border-radius: 8px; font-family: monospace; font-size: 12px; color: #60a5fa; cursor: pointer;
    transition: background 0.2s, border-color 0.2s;
  }
  .ip-chip:hover { background: #1e293b; border-color: #60a5fa; color: white; }
  .header-actions { display: flex; gap: 10px; align-items: center; }

  /* STATUS BADGE */
  .status-badge {
    padding: 8px 16px; border-radius: 999px; font-weight: 800; font-size: 13px;
    display: inline-flex; align-items: center; gap: 8px; letter-spacing: 0.5px;
  }
  .online { background: var(--green-bg); color: #34d399; border: 1px solid #10b981; box-shadow: 0 0 12px rgba(16,185,129,0.3); }
  .offline { background: var(--red-bg); color: #fb7185; border: 1px solid #f43f5e; box-shadow: 0 0 12px rgba(244,63,94,0.3); }
  .pulse-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; animation: pulse 1.8s infinite; }
  @keyframes pulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(0.85); } }

  /* OVERVIEW SUMMARY STRIP */
  .status-strip {
    display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 12px; margin-bottom: 20px;
  }
  .status-card {
    background: var(--card); border: 1px solid var(--border); border-radius: 12px;
    padding: 14px 16px; display: flex; flex-direction: column; gap: 4px;
    position: relative; overflow: hidden;
  }
  .status-card-label { font-size: 11px; text-transform: uppercase; font-weight: 700; color: var(--muted); letter-spacing: 0.8px; }
  .status-card-value { font-size: 16px; font-weight: 800; display: flex; align-items: center; gap: 6px; }
  .tag-green { color: #34d399; }
  .tag-red { color: #fb7185; }
  .tag-amber { color: #fbbf24; }
  .tag-blue { color: #60a5fa; }

  /* MAIN GRID */
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(370px, 1fr)); gap: 20px; }
  .card {
    background: var(--card); border: 1px solid var(--border); border-radius: 16px;
    padding: 20px; display: flex; flex-direction: column; gap: 14px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.25);
  }
  .card h2 {
    margin: 0; font-size: 17px; font-weight: 700; display: flex; align-items: center;
    gap: 8px; border-bottom: 1px solid var(--border); padding-bottom: 12px; color: #f1f5f9;
  }

  /* TOGGLE ROW */
  .rule-box {
    background: #0b1120; border: 1px solid var(--border); border-radius: 12px;
    padding: 14px; display: flex; justify-content: space-between; align-items: center; gap: 10px;
    transition: border-color 0.2s;
  }
  .rule-box:hover { border-color: var(--border-light); }
  .rule-desc-title { font-weight: 700; font-size: 14px; display: flex; align-items: center; gap: 6px; }
  .rule-desc-sub { font-size: 12px; color: var(--muted); margin-top: 2px; }
  
  .pill-state {
    padding: 5px 12px; border-radius: 999px; font-size: 11px; font-weight: 800;
    text-transform: uppercase; letter-spacing: 0.5px;
  }
  .pill-on { background: var(--green-bg); color: #34d399; border: 1px solid #10b981; }
  .pill-off { background: var(--red-bg); color: #fb7185; border: 1px solid #f43f5e; }

  /* BUTTONS */
  .btn-row { display: flex; flex-wrap: wrap; gap: 8px; }
  button {
    cursor: pointer; border: none; border-radius: 10px; padding: 10px 16px;
    font-weight: 700; font-size: 13px; color: white; background: var(--accent);
    display: inline-flex; align-items: center; justify-content: center; gap: 6px;
    transition: all 0.15s ease;
  }
  button:hover { filter: brightness(1.1); transform: translateY(-1px); }
  button:active { transform: translateY(0); filter: brightness(0.95); }
  .btn-green { background: #059669; }
  .btn-green:hover { background: #10b981; }
  .btn-red { background: #e11d48; }
  .btn-red:hover { background: #f43f5e; }
  .btn-amber { background: #d97706; color: #fff; }
  .btn-amber:hover { background: #f59e0b; }
  .btn-dark { background: #334155; }
  .btn-dark:hover { background: #475569; }
  .btn-sm { padding: 6px 10px; font-size: 12px; border-radius: 8px; }
  .btn-toggle-action { min-width: 90px; }

  /* INPUTS */
  input, select {
    background: #090d16; border: 1px solid var(--border); color: white;
    padding: 10px 14px; border-radius: 10px; font-size: 13px; outline: none;
    transition: border-color 0.2s;
  }
  input:focus, select:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }
  .input-group { display: flex; gap: 8px; }
  
  /* PLAYER LIST ITEMS */
  .player-card {
    background: #0b1120; border: 1px solid var(--border); border-radius: 12px;
    padding: 12px 14px; display: flex; justify-content: space-between; align-items: center;
    gap: 8px; margin-bottom: 8px;
  }
  .player-info { display: flex; align-items: center; gap: 10px; }
  .player-avatar {
    width: 32px; height: 32px; border-radius: 8px; background: #1e293b;
    display: flex; align-items: center; justify-content: center; font-weight: 800; color: #60a5fa;
  }
  .player-name { font-weight: 700; font-size: 14px; }
  .player-sub { font-size: 11px; color: var(--muted); }

  /* LOG CONSOLE */
  .log-box {
    background: #050811; color: #38bdf8; font-family: 'Consolas', 'Courier New', monospace;
    font-size: 12px; padding: 14px; border-radius: 10px; height: 260px;
    overflow-y: auto; white-space: pre-wrap; border: 1px solid var(--border);
    line-height: 1.5;
  }
  
  /* TOAST NOTIFICATION */
  .toast {
    position: fixed; bottom: 24px; right: 24px; background: #1e293b;
    border-left: 5px solid var(--green); color: white; padding: 14px 22px;
    border-radius: 10px; box-shadow: 0 12px 30px rgba(0,0,0,0.6);
    display: none; z-index: 1000; font-weight: 600; font-size: 14px;
    animation: fadeIn 0.2s ease;
  }
  @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

  .friends-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
  .friend-chip {
    background: #1e293b; border: 1px solid var(--border-light); padding: 4px 10px;
    border-radius: 999px; font-size: 12px; font-weight: 700; display: inline-flex;
    align-items: center; gap: 6px;
  }
  .friend-chip button {
    background: transparent; color: #fb7185; padding: 0; width: 16px; height: 16px;
    font-size: 12px; border-radius: 50%;
  }
  .friend-chip button:hover { background: rgba(244,63,94,0.2); }
</style>
</head>
<body>

<!-- TOP HEADER -->
<div class="header">
  <div class="header-info">
    <h1>🛡️ Servidor Eaglercraft — Consola de Xavi</h1>
    <div class="header-subtitle">
      <span>Mundo: <b>New World (8).epk</b></span>
      <span>•</span>
      <span id="badgeServerMode" class="ip-chip" style="background:#1e293b; color:#38bdf8; font-weight:700;">Modo: Colegio (Hotspot)</span>
      <span>•</span>
      <span>Para ti: <span class="ip-chip" onclick="copyText('ws://localhost:8081')">ws://localhost:8081 📋</span></span>
      <span>•</span>
      <span id="friendsConnectionBox">Para amigos (Hotspot): <span class="ip-chip" onclick="copyText('ws://192.168.137.1:8081')">ws://192.168.137.1:8081 📋</span></span>
    </div>
  </div>
  <div class="header-actions">
    <div id="statusBadge" class="status-badge online"><div class="pulse-dot"></div> Conectando...</div>
    <button id="btnStartServer" class="btn-green" style="display:none;" onclick="startServer()">▶️ Encender Servidor</button>
    <button id="btnSaveWorld" class="btn-green" onclick="runCmd('guardar')">💾 Guardar Mundo</button>
    <button id="btnStopServer" class="btn-red" onclick="stopServer()">🛑 Apagar Servidor</button>
  </div>
</div>

<!-- LIVE METRIC STRIP -->
<div class="status-strip">
  <div class="status-card">
    <div class="status-card-label">Estado del Servidor</div>
    <div id="cardServerStatus" class="status-card-value tag-green">🟢 ONLINE</div>
  </div>
  <div class="status-card">
    <div class="status-card-label">Jugadores Conectados</div>
    <div id="cardPlayerCount" class="status-card-value tag-blue">0 / 20</div>
  </div>
  <div class="status-card">
    <div class="status-card-label">Auto-Ban al Romper Casa</div>
    <div id="cardAutoBanStatus" class="status-card-value tag-green">🚨 ACTIVADO</div>
  </div>
  <div class="status-card">
    <div class="status-card-label">Protección de Cofres</div>
    <div id="cardChestsStatus" class="status-card-value tag-green">🔒 BLOQUEADOS</div>
  </div>
  <div class="status-card">
    <div class="status-card-label">Anti-Explosiones (TNT/Creeper)</div>
    <div id="cardExplosionStatus" class="status-card-value tag-green">🛡️ SEGURO</div>
  </div>
  <div class="status-card">
    <div class="status-card-label">PvP Global</div>
    <div id="cardPvpStatus" class="status-card-value tag-amber">⚔️ ACTIVADO</div>
  </div>
  <div class="status-card">
    <div class="status-card-label">Auto-Apagado Inactivo</div>
    <div id="cardIdleStatus" class="status-card-value tag-blue">⏱️ 10 MIN</div>
    <div id="cardIdleSub" style="font-size:11px; color:var(--muted); margin-top:2px;">PC en reposo</div>
  </div>
</div>

<!-- MAIN GRID OF CARDS -->
<div class="grid">

  <!-- 1. PROTECCIÓN DE CASA Y AUTO-BAN -->
  <div class="card">
    <h2>🏠 Reglas de tu Casa y Auto-Ban</h2>
    
    <!-- Auto-Ban Toggle -->
    <div class="rule-box">
      <div>
        <div class="rule-desc-title">🚨 Auto-Ban al Romper tu Casa</div>
        <div class="rule-desc-sub">Si un amigo intenta romper un bloque de tu casa, el bloque NO se rompe y el servidor lo banea al instante.</div>
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        <span id="badgeAutoBan" class="pill-state pill-on">ACTIVO</span>
        <button id="btnToggleAutoBan" class="btn-sm btn-dark btn-toggle-action" onclick="toggleRule('autoban')">Cambiar</button>
      </div>
    </div>

    <!-- Auto-Shutdown Inactivity Toggle -->
    <div class="rule-box">
      <div>
        <div class="rule-desc-title">⏱️ Auto-Apagado por Inactividad (10 min)</div>
        <div class="rule-desc-sub">Guarda todo y apaga el servidor automáticamente si no hay nadie conectado durante 10 minutos para liberar tu PC.</div>
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        <span id="badgeAutoShutdown" class="pill-state pill-on">10 MIN</span>
        <button id="btnToggleAutoShutdown" class="btn-sm btn-dark btn-toggle-action" onclick="toggleAutoShutdown()">Cambiar</button>
      </div>
    </div>

    <!-- Cofres Toggle -->
    <div class="rule-box">
      <div>
        <div class="rule-desc-title">🔒 Bloqueo de Cofres y Hornos</div>
        <div class="rule-desc-sub">Impide que amigos abran o saquen objetos de tus cofres y hornos en tu casa.</div>
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        <span id="badgeChests" class="pill-state pill-on">BLOQUEADOS</span>
        <button id="btnToggleChests" class="btn-sm btn-dark btn-toggle-action" onclick="toggleRule('cofres')">Cambiar</button>
      </div>
    </div>

    <!-- Anti-Explosion Toggle -->
    <div class="rule-box">
      <div>
        <div class="rule-desc-title">💥 Protección Anti-Explosiones</div>
        <div class="rule-desc-sub">Los Creepers y la TNT no rompen bloques en el mundo.</div>
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        <span id="badgeExplosion" class="pill-state pill-on">SEGURO</span>
        <button id="btnToggleExplosion" class="btn-sm btn-dark btn-toggle-action" onclick="toggleRule('explosion')">Cambiar</button>
      </div>
    </div>

    <!-- Ubicación Casa -->
    <div style="background:#0b1120; border:1px solid var(--border); border-radius:12px; padding:14px;">
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <div>
          <div style="font-weight:700; font-size:13px; color:#f1f5f9;">📍 Ubicación de la Casa Protegida:</div>
          <div id="houseCoords" style="font-size:12px; color:#60a5fa; font-family:monospace; margin-top:2px;">Centro: X=-276, Y=67, Z=-10 (Radio: 32 bloques)</div>
        </div>
        <button class="btn-sm btn-dark" onclick="runCmd('casa')">🚀 Ir a mi Casa</button>
      </div>
      <div style="display:flex; gap:8px; margin-top:10px;">
        <input id="inputRadius" type="number" placeholder="Radio (ej. 35)" style="width:110px;" value="32">
        <button class="btn-sm btn-green" onclick="setHouseHere()">📍 Fijar Casa donde está Xavi ahora</button>
      </div>
    </div>

    <!-- Amigos de Confianza -->
    <div style="background:#0b1120; border:1px solid var(--border); border-radius:12px; padding:14px;">
      <div style="font-weight:700; font-size:13px; color:#f1f5f9;">🤝 Amigos con Permiso en tu Casa:</div>
      <div style="font-size:11px; color:var(--muted); margin-top:2px;">Estos amigos pueden construir y abrir cofres sin ser auto-baneados.</div>
      <div id="friendsList" class="friends-chips">Cargando amigos...</div>
      <div class="input-group" style="margin-top:10px;">
        <input id="friendInput" placeholder="Nombre del amigo (ej. Jeffepstein, taetti)..." style="flex:1;">
        <button class="btn-sm btn-green" onclick="addFriend()">+ Dar Permiso</button>
      </div>
    </div>
  </div>

  <!-- 2. JUGADORES CONECTADOS Y CONTROL -->
  <div class="card">
    <h2>👥 Jugadores Conectados y Acciones</h2>
    <div style="font-size:12px; color:var(--muted);">Controla a cada jugador que entra al servidor en tiempo real.</div>

    <div id="onlinePlayersContainer">
      <div style="color:var(--muted); font-size:13px; text-align:center; padding:20px;">Comprobando jugadores conectados...</div>
    </div>

    <!-- ACCIÓN RÁPIDA MANUAL -->
    <div style="background:#0b1120; border:1px solid var(--border); border-radius:12px; padding:14px; margin-top:6px;">
      <div style="font-weight:700; font-size:13px; margin-bottom:8px;">🎯 Acción Rápida sobre cualquier Jugador:</div>
      <div class="input-group">
        <input id="quickTarget" list="knownPlayersList" placeholder="Escribe o selecciona jugador..." style="flex:1;">
        <datalist id="knownPlayersList"></datalist>
        <input id="quickReason" placeholder="Motivo (opcional)..." style="flex:1;">
      </div>
      <div class="btn-row" style="margin-top:10px;">
        <button class="btn-amber btn-sm" onclick="quickWarn()">⚠️ Advertir (/warn)</button>
        <button class="btn-dark btn-sm" onclick="quickUnwarn()">↩️ Quitar Warn (-1)</button>
        <button class="btn-red btn-sm" onclick="quickBan()">🔨 Banear</button>
        <button class="btn-green btn-sm" onclick="quickUnban()">✅ Desbanear</button>
        <button class="btn-amber btn-sm" onclick="quickKick()">👢 Expulsar (Kick)</button>
        <button class="btn-dark btn-sm" onclick="quickFreeze()">❄️ Congelar / Descongelar</button>
        <button class="btn-dark btn-sm" onclick="quickTpToMe()">📍 Traer hacia mí</button>
      </div>
    </div>

    <!-- LISTA DE ADVERTENCIAS ACTIVAS -->
    <div style="margin-top:8px;">
      <div style="font-weight:700; font-size:13px; margin-bottom:6px; color:#fbbf24;">⚠️ Historial de Advertencias Activas (Auto-Ban a las 4):</div>
      <div id="warnedListContainer">Cargando advertencias...</div>
    </div>

    <!-- LISTA DE BANEADOS -->
    <div style="margin-top:10px;">
      <div style="font-weight:700; font-size:13px; margin-bottom:6px; color:#f87171;">🚫 Lista de Jugadores Baneados:</div>
      <div id="bannedListContainer">Cargando lista de baneados...</div>
    </div>
  </div>

  <!-- 3. PODERES DE XAVI Y REGLAS DEL MUNDO -->
  <div class="card">
    <h2>⚡ Poderes de Xavi y Control del Mundo</h2>
    
    <div class="rule-box">
      <div>
        <div class="rule-desc-title">⚔️ Modo PvP Global (Peleas entre jugadores)</div>
        <div class="rule-desc-sub">Permite o prohíbe que los jugadores se puedan hacer daño entre ellos.</div>
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        <span id="badgePvp" class="pill-state pill-on">PVP ON</span>
        <button id="btnTogglePvp" class="btn-sm btn-dark btn-toggle-action" onclick="toggleRule('pvp')">Cambiar</button>
      </div>
    </div>

    <div style="font-weight:700; font-size:13px; color:#f1f5f9; margin-top:4px;">Poderes Rápidos para Xavi:</div>
    <div class="btn-row">
      <button class="btn-green" onclick="runCmd('god Xavi')">🛡️ Modo Inmortal (God)</button>
      <button onclick="runCmd('fly Xavi')">🕊️ Modo Vuelo (Fly)</button>
      <button class="btn-green" onclick="runCmd('curar Xavi')">❤️ Curar Vida y Comida 100%</button>
      <button class="btn-amber" onclick="runCmd('recuperarinventario')">🎒 Restaurar Inventario del EPK</button>
    </div>

    <div style="font-weight:700; font-size:13px; color:#f1f5f9; margin-top:6px;">Modo de Juego de Xavi:</div>
    <div class="btn-row">
      <button class="btn-dark" onclick="runCmd('gamemode 0 Xavi')">⚔️ Supervivencia</button>
      <button class="btn-amber" onclick="runCmd('gamemode 1 Xavi')">🧱 Creativo</button>
      <button class="btn-dark" onclick="runCmd('gamemode 3 Xavi')">👻 Espectador</button>
    </div>

    <div style="font-weight:700; font-size:13px; color:#f1f5f9; margin-top:6px;">Tiempo y Clima:</div>
    <div class="btn-row">
      <button class="btn-dark" onclick="runCmd('time set day'); runCmd('weather clear')">☀️ Hacer de Día y Soleado</button>
      <button class="btn-dark" onclick="runCmd('time set night')">🌙 Hacer de Noche</button>
    </div>

    <div style="font-weight:700; font-size:13px; color:#f1f5f9; margin-top:6px;">Copias de Seguridad y Rescate del Mundo:</div>
    <div class="btn-row">
      <button class="btn-green" onclick="runCmd('guardar')">💾 Guardar Mundo en Disco</button>
      <button class="btn-dark" onclick="createBackup()">📦 Crear Copia de Seguridad</button>
      <button class="btn-red" onclick="restoreOriginalWorld()">♻️ Restaurar Mundo Original (EPK)</button>
    </div>
  </div>

  
  <!-- CARD: ALIANZAS Y GUERRAS -->
  <div class="card" style="border-top:3px solid #3b82f6;">
    <div style="display:flex; justify-content:space-between; align-items:center;">
      <h2>🛡️ Alianzas, Clanes y Guerras Activas</h2>
      <button class="btn-sm btn-dark" onclick="runCmd('alianza lista')">📜 /alianza lista</button>
    </div>
    <div style="font-size:12px; color:var(--muted); margin-bottom:12px;">Forma clanes, defiende a tus aliados, activa/desactiva fuego amigo y enfréntate a otras facciones.</div>

    <div id="alliancesContainer" style="display:grid; grid-template-columns:repeat(auto-fill, minmax(280px, 1fr)); gap:12px;">
      <div style="color:var(--muted); font-size:13px; padding:16px;">Comprobando alianzas...</div>
    </div>
  </div>

  <!-- CARD: SISTEMA DE HONOR RDR2 -->
  <div class="card" style="border-top:3px solid #10b981;">
    <div style="display:flex; justify-content:space-between; align-items:center;">
      <h2>🤠 Sistema de Honor (Estilo Red Dead Redemption 2)</h2>
      <button class="btn-sm btn-dark" onclick="runCmd('honor Xavi')">⚖️ Mi Honor</button>
    </div>
    <div style="font-size:12px; color:var(--muted); margin-bottom:12px;">Cada acto cuenta. Proteger a otros y cazar monstruos aumenta tu honor. Matar inocentes y recibir advertencias te convierte en forajido buscado.</div>

    <div id="honorListContainer" style="display:flex; flex-direction:column; gap:10px;">
      <div style="color:var(--muted); font-size:13px; padding:16px;">Cargando honor de jugadores...</div>
    </div>

    <div style="margin-top:14px; background:#0b1120; border:1px solid var(--border); border-radius:10px; padding:12px; font-size:12px; color:var(--muted); display:flex; justify-content:space-around; flex-wrap:wrap; gap:10px;">
      <div><span style="color:#10b981; font-weight:700;">▲ Subir Honor:</span> Cazar monstruos (+2), donar ítems con /donar (+10), cazar forajidos (+15)</div>
      <div><span style="color:#ef4444; font-weight:700;">▼ Bajar Honor:</span> Matar inocentes (-25), grifear (-50), recibir advertencia /warn (-20)</div>
    </div>
  </div>

  <!-- 4. CONSOLA EN VIVO -->
  <div class="card">
    <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:12px;">
      <h2 style="margin:0; border:none; padding:0;">💻 Consola del Servidor en Vivo</h2>
      <button class="btn-sm btn-dark" onclick="clearConsole()">🧹 Limpiar</button>
    </div>
    <div id="logBox" class="log-box">Conectando a los registros del servidor...</div>
    <div class="input-group">
      <input id="customCmd" placeholder="Escribe cualquier comando (ej. tp Jeffepstein Xavi, give Xavi diamond 64)..." onkeydown="if(event.key==='Enter') sendCustomCmd()">
      <button onclick="sendCustomCmd()">▶ Ejecutar</button>
    </div>
    <div id="cmdOutput" style="font-size:13px; color:#34d399; min-height:18px; font-weight:600;"></div>
    
    <div style="display:flex; gap:6px; flex-wrap:wrap; font-size:11px; color:var(--muted); align-items:center;">
      <span>Comandos rápidos:</span>
      <button class="btn-sm btn-dark" style="padding:4px 8px; font-size:11px;" onclick="runCmd('list')">/list</button>
      <button class="btn-sm btn-dark" style="padding:4px 8px; font-size:11px;" onclick="runCmd('infocasa')">/infocasa</button>
      <button class="btn-sm btn-dark" style="padding:4px 8px; font-size:11px;" onclick="runCmd('baneados')">/baneados</button>
      <button class="btn-sm btn-dark" style="padding:4px 8px; font-size:11px;" onclick="runCmd('warns')">/warns</button>
      <button class="btn-sm btn-dark" style="padding:4px 8px; font-size:11px;" onclick="runCmd('recuperarinventario')">/recuperarinventario</button>
    </div>
  </div>

</div>

<div id="toast" class="toast"></div>

<script>
let currentRules = {};

function showToast(msg, isError = false) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.style.borderLeftColor = isError ? '#f43f5e' : '#10b981';
  t.style.display = 'block';
  setTimeout(() => t.style.display = 'none', 3800);
}

function copyText(txt) {
  navigator.clipboard.writeText(txt).then(() => {
    showToast('¡Copiado al portapapeles: ' + txt + '!');
  });
}

async function runCmd(cmd) {
  try {
    const res = await fetch('/api/cmd', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cmd })
    });
    const data = await res.json();
    const out = data.output || ('Comando ejecutado: ' + cmd);
    document.getElementById('cmdOutput').textContent = '➜ ' + out;
    showToast(out);
    refreshState();
  } catch (e) {
    showToast('Error al conectar con el servidor', true);
  }
}

function sendCustomCmd() {
  const el = document.getElementById('customCmd');
  if (!el.value.trim()) return;
  runCmd(el.value.trim());
  el.value = '';
}

function clearConsole() {
  document.getElementById('logBox').textContent = '';
}

async function toggleRule(ruleType) {
  let targetCmd = '';
  if (ruleType === 'autoban') {
    targetCmd = currentRules.autoBan ? 'autobancasa off' : 'autobancasa on';
  } else if (ruleType === 'cofres') {
    targetCmd = currentRules.protectChests ? 'cofrescasa off' : 'cofrescasa on';
  } else if (ruleType === 'explosion') {
    targetCmd = currentRules.antiExplosion ? 'regla explosion on' : 'regla explosion off';
  } else if (ruleType === 'pvp') {
    targetCmd = 'pvp';
  }
  if (targetCmd) {
    await runCmd(targetCmd);
  }
}

function setHouseHere() {
  const r = document.getElementById('inputRadius').value || '32';
  runCmd('protegercasa ' + r);
}

function addFriend() {
  const f = document.getElementById('friendInput').value.trim();
  if (!f) return alert('Escribe el nombre de tu amigo');
  runCmd('amigos add ' + f);
  document.getElementById('friendInput').value = '';
}

function removeFriend(f) {
  runCmd('amigos remove ' + f);
}

function quickWarn() {
  const p = document.getElementById('quickTarget').value.trim();
  const r = document.getElementById('quickReason').value.trim() || 'Comportamiento inadecuado con otros jugadores';
  if (!p) return alert('Escribe o selecciona el nombre del jugador');
  runCmd('warn ' + p + ' ' + r);
}

function quickWarnUser(name) {
  const reason = prompt('Motivo de la advertencia para ' + name + ' (a las 4 advertencias se auto-banea):', 'Comportamiento inadecuado con otros jugadores');
  if (reason !== null && reason.trim()) {
    runCmd('warn ' + name + ' ' + reason.trim());
  }
}

function quickUnwarn(name) {
  const p = name || document.getElementById('quickTarget').value.trim();
  if (!p) return alert('Escribe o selecciona el nombre del jugador');
  runCmd('unwarn ' + p);
}

function quickClearWarns(name) {
  if (!confirm('¿Seguro que deseas borrar todas las advertencias de ' + name + '?')) return;
  runCmd('clearwarns ' + name);
}

function quickBan() {
  const p = document.getElementById('quickTarget').value.trim();
  const r = document.getElementById('quickReason').value.trim() || 'Baneado desde la consola de Xavi';
  if (!p) return alert('Escribe el nombre del jugador');
  if (!confirm('¿Seguro que quieres banear a ' + p + '?')) return;
  runCmd('ban ' + p + ' ' + r);
}

function quickUnban(name) {
  const p = name || document.getElementById('quickTarget').value.trim();
  if (!p) return alert('Escribe el nombre del jugador');
  runCmd('unban ' + p);
}

function quickKick() {
  const p = document.getElementById('quickTarget').value.trim();
  const r = document.getElementById('quickReason').value.trim() || 'Expulsado por Xavi';
  if (!p) return alert('Escribe el nombre del jugador');
  runCmd('expulsar ' + p + ' ' + r);
}

function quickFreeze() {
  const p = document.getElementById('quickTarget').value.trim();
  if (!p) return alert('Escribe el nombre del jugador');
  runCmd('congelar ' + p);
}

function quickTpToMe() {
  const p = document.getElementById('quickTarget').value.trim();
  if (!p) return alert('Escribe el nombre del jugador');
  runCmd('tp ' + p + ' Xavi');
}


async function toggleAutoShutdown() {
  await fetch('/api/toggle-autoshutdown', { method: 'POST' });
  showToast('Cambiando Auto-Apagado por inactividad...');
  refreshState();
}


async function startServer() {
  showToast('Iniciando servidor de Minecraft y Proxy...');
  await fetch('/api/start', { method: 'POST' });
  setTimeout(refreshState, 3500);
}

async function stopServer() {
  if (!confirm('¿Seguro que deseas guardar el mundo y apagar todo el servidor?')) return;
  showToast('Guardando mundo e inventarios limpiamente...');
  try {
    await fetch('/api/stop', { method: 'POST' });
  } catch (ignored) {}
  setTimeout(() => {
    document.body.innerHTML = '<div style="display:flex;height:100vh;justify-content:center;align-items:center;flex-direction:column;font-family:sans-serif;color:#f1f5f9;background:#090d16;text-align:center;padding:20px;">'
      + '<div style="font-size:48px;margin-bottom:16px;">🛑</div>'
      + '<h2 style="font-size:24px;margin-bottom:8px;">Servidor y Consola Apagados</h2>'
      + '<p style="color:#94a3b8;max-width:480px;line-height:1.6;">El mundo e inventarios se han guardado con éxito en el disco. Todos los puertos y servicios se han liberado por completo. Ya puedes cerrar esta ventana.</p>'
      + '</div>';
  }, 1000);
}

async function createBackup() {
  showToast('Creando copia de seguridad...');
  await runCmd('guardar');
  const res = await fetch('/api/backup', { method: 'POST' });
  const data = await res.json();
  showToast(data.message || 'Copia de seguridad creada con éxito.');
}

async function restoreOriginalWorld() {
  if (!confirm('ATENCIÓN: Esto restaurará el mundo exactamente a como estaba en tu archivo E:\\Eaglercraft\\New World (8).epk. ¿Deseas continuar?')) return;
  showToast('Restaurando mundo original...');
  const res = await fetch('/api/restore', { method: 'POST' });
  const data = await res.json();
  showToast(data.message || 'Mundo restaurado con éxito.');
}

async function refreshState() {
  try {
    const res = await fetch('/api/state');
    const data = await res.json();
    
    // Auto-shutdown status
    const bShutdown = document.getElementById('badgeAutoShutdown');
    const cIdle = document.getElementById('cardIdleStatus');
    const cIdleSub = document.getElementById('cardIdleSub');
    const btnShutdown = document.getElementById('btnToggleAutoShutdown');
    if (data.autoShutdownEnabled) {
      if (bShutdown) { bShutdown.textContent = '10 MIN (ON)'; bShutdown.className = 'pill-state pill-on'; }
      if (btnShutdown) btnShutdown.textContent = 'Desactivar';
      if (data.online) {
        if (!data.players || data.players.length === 0) {
          const left = Math.max(0, 600 - (data.idleSeconds || 0));
          const mins = Math.floor(left / 60);
          const secs = left % 60;
          if (cIdle) {
            cIdle.textContent = '⏱️ ' + mins + ':' + (secs < 10 ? '0' : '') + secs;
            cIdle.className = 'status-card-value tag-amber';
          }
          if (cIdleSub) cIdleSub.textContent = 'Apagando por inactividad...';
        } else {
          if (cIdle) {
            cIdle.textContent = '⏱️ ACTIVO (10m)';
            cIdle.className = 'status-card-value tag-green';
          }
          if (cIdleSub) cIdleSub.textContent = 'Pausado (hay jugadores)';
        }
      } else {
        if (cIdle) { cIdle.textContent = '💤 APAGADO'; cIdle.className = 'status-card-value tag-muted'; }
        if (cIdleSub) cIdleSub.textContent = 'PC en reposo';
      }
    } else {
      if (bShutdown) { bShutdown.textContent = 'DESACTIVADO'; bShutdown.className = 'pill-state pill-off'; }
      if (btnShutdown) btnShutdown.textContent = 'Activar';
      if (cIdle) { cIdle.textContent = '⛔ OFF'; cIdle.className = 'status-card-value tag-red'; }
      if (cIdleSub) cIdleSub.textContent = 'Sin auto-apagado';
    }

    // Server Online / Offline
    const badge = document.getElementById('statusBadge');
    const cardStatus = document.getElementById('cardServerStatus');
    const btnStart = document.getElementById('btnStartServer');
    const btnStop = document.getElementById('btnStopServer');
    const btnSave = document.getElementById('btnSaveWorld');

    // Connection Mode and Public URL
    const bMode = document.getElementById('badgeServerMode');
    const friendsBox = document.getElementById('friendsConnectionBox');
    if (data.modo === 'casa') {
      if (bMode) { bMode.textContent = '🏠 Modo Casa (Cloudflare)'; bMode.style.color = '#10b981'; }
      if (friendsBox) {
        if (data.tunnelUrl && data.tunnelUrl.ws) {
          friendsBox.innerHTML = 'Para amigos: <span class="ip-chip" onclick="copyText(\'' + data.tunnelUrl.ws + '\')">' + data.tunnelUrl.ws + ' 📋</span>'
            + ' <span style="margin:0 4px">•</span> Web: <span class="ip-chip" onclick="copyText(\'' + data.tunnelUrl.web + '\')">' + data.tunnelUrl.web + ' 📋</span>';
        } else {
          friendsBox.innerHTML = 'Para amigos: <span class="ip-chip" style="color:#fbbf24;">⏳ Conectando túnel Cloudflare...</span>';
        }
      }
    } else if (data.modo === 'ngrok') {
      if (bMode) { bMode.textContent = '🌐 Modo Casa (Ngrok)'; bMode.style.color = '#38bdf8'; }
      if (friendsBox) {
        if (data.tunnelUrl && data.tunnelUrl.ws) {
          friendsBox.innerHTML = 'Para amigos: <span class="ip-chip" onclick="copyText(\'' + data.tunnelUrl.ws + '\')">' + data.tunnelUrl.ws + ' 📋</span>';
        } else {
          friendsBox.innerHTML = 'Para amigos: <span class="ip-chip" style="color:#fbbf24;">⏳ Conectando Ngrok...</span>';
        }
      }
    } else {
      if (bMode) { bMode.textContent = '🏫 Modo Colegio (Hotspot)'; bMode.style.color = '#f59e0b'; }
      if (friendsBox) {
        friendsBox.innerHTML = 'Para amigos (Hotspot): <span class="ip-chip" onclick="copyText(\'ws://192.168.137.1:8081\')">ws://192.168.137.1:8081 📋</span>';
      }
    }

    if (data.online) {
      badge.innerHTML = '<div class="pulse-dot"></div> 🟢 SERVIDOR ONLINE';
      badge.className = 'status-badge online';
      cardStatus.innerHTML = '🟢 ONLINE';
      cardStatus.className = 'status-card-value tag-green';
      if (btnStart) btnStart.style.display = 'none';
      if (btnStop) btnStop.style.display = 'inline-flex';
      if (btnSave) btnSave.style.display = 'inline-flex';
    } else {
      badge.innerHTML = '<div class="pulse-dot"></div> 🔴 SERVIDOR APAGADO';
      badge.className = 'status-badge offline';
      cardStatus.innerHTML = '🔴 APAGADO';
      cardStatus.className = 'status-card-value tag-red';
      if (btnStart) btnStart.style.display = 'inline-flex';
      if (btnStop) btnStop.style.display = 'none';
      if (btnSave) btnSave.style.display = 'none';
    }

    // Rules state
    if (data.rules) {
      currentRules = data.rules;

      // Auto-Ban
      const bAutoBan = document.getElementById('badgeAutoBan');
      const cAutoBan = document.getElementById('cardAutoBanStatus');
      const btnAutoBan = document.getElementById('btnToggleAutoBan');
      if (data.rules.autoBan) {
        bAutoBan.textContent = '🚨 ACTIVO';
        bAutoBan.className = 'pill-state pill-on';
        cAutoBan.textContent = '🚨 ACTIVADO';
        cAutoBan.className = 'status-card-value tag-green';
        btnAutoBan.textContent = 'Desactivar';
      } else {
        bAutoBan.textContent = '⚠️ SOLO BLOQUEAR';
        bAutoBan.className = 'pill-state pill-off';
        cAutoBan.textContent = '⚠️ SOLO BLOQUEAR';
        cAutoBan.className = 'status-card-value tag-amber';
        btnAutoBan.textContent = 'Activar Ban';
      }

      // Protect Chests
      const bChests = document.getElementById('badgeChests');
      const cChests = document.getElementById('cardChestsStatus');
      const btnChests = document.getElementById('btnToggleChests');
      if (data.rules.protectChests) {
        bChests.textContent = '🔒 BLOQUEADOS';
        bChests.className = 'pill-state pill-on';
        cChests.textContent = '🔒 BLOQUEADOS';
        cChests.className = 'status-card-value tag-green';
        btnChests.textContent = 'Abrir a todos';
      } else {
        bChests.textContent = '🔓 ABIERTOS';
        bChests.className = 'pill-state pill-off';
        cChests.textContent = '🔓 ABIERTOS';
        cChests.className = 'status-card-value tag-amber';
        btnChests.textContent = 'Bloquear';
      }

      // Anti-Explosion
      const bExp = document.getElementById('badgeExplosion');
      const cExp = document.getElementById('cardExplosionStatus');
      const btnExp = document.getElementById('btnToggleExplosion');
      if (data.rules.antiExplosion) {
        bExp.textContent = '🛡️ SEGURO';
        bExp.className = 'pill-state pill-on';
        cExp.textContent = '🛡️ SEGURO';
        cExp.className = 'status-card-value tag-green';
        btnExp.textContent = 'Permitir daño';
      } else {
        bExp.textContent = '💥 DESTRUCTORAS';
        bExp.className = 'pill-state pill-off';
        cExp.textContent = '💥 DESTRUCTORAS';
        cExp.className = 'status-card-value tag-red';
        btnExp.textContent = 'Proteger';
      }

      // House Zone info
      if (data.rules.zone) {
        const z = data.rules.zone;
        document.getElementById('houseCoords').textContent = 
          'Centro: X=' + z.x + ', Y=' + z.y + ', Z=' + z.z + ' (Radio: ' + z.radius + ' bloques)';
        if (document.activeElement !== document.getElementById('inputRadius')) {
          document.getElementById('inputRadius').value = z.radius;
        }
      }

      // Trusted friends
      const fList = document.getElementById('friendsList');
      if (data.rules.friends && data.rules.friends.length > 0) {
        fList.innerHTML = data.rules.friends.map(f => 
          '<span class="friend-chip">'
          + '👤 ' + f + ' '
          + '<button onclick="removeFriend(\'' + f + '\')" title="Quitar permiso">✕</button>'
          + '</span>'
        ).join('');
      } else {
        fList.innerHTML = '<span style="color:var(--muted); font-size:12px;">Ningún amigo añadido (solo Xavi puede construir y abrir cofres).</span>';
      }
    }

    // Players Online list
    const pContainer = document.getElementById('onlinePlayersContainer');
    const pCount = document.getElementById('cardPlayerCount');
    const onlinePlayers = data.players || [];
    pCount.textContent = onlinePlayers.length + ' / 20';

    if (onlinePlayers.length > 0) {
      pContainer.innerHTML = onlinePlayers.map(p => {
        const isXavi = p.toLowerCase() === 'xavi';
        return '<div class="player-card">'
          + '<div class="player-info">'
          + '<div class="player-avatar">' + p.substring(0, 2).toUpperCase() + '</div>'
          + '<div>'
          + '<div class="player-name">' + p + (isXavi ? ' <span style="color:#f59e0b;font-size:11px;">[ADMIN]</span>' : '') + '</div>'
          + '<div class="player-sub">Conectado en vivo</div>'
          + '</div>'
          + '</div>'
          + '<div class="btn-row">'
          + '<button class="btn-dark btn-sm" onclick="runCmd(\'tp ' + p + ' Xavi\')">📍 Traer</button>'
          + '<button class="btn-dark btn-sm" onclick="runCmd(\'congelar ' + p + '\')">❄️ Congelar</button>'
          + '<button class="btn-dark btn-sm" onclick="runCmd(\'curar ' + p + '\')">❤️ Curar</button>'
          + (!isXavi ? '<button class="btn-amber btn-sm" onclick="quickWarnUser(\'' + p + '\')">⚠️ Warn</button>' : '')
          + '<button class="btn-amber btn-sm" onclick="runCmd(\'expulsar ' + p + '\')">Kick</button>'
          + (!isXavi ? '<button class="btn-red btn-sm" onclick="quickBanUser(\'' + p + '\')">🔨 Ban</button>' : '')
          + '</div>'
          + '</div>';
      }).join('');
    } else {
      pContainer.innerHTML = '<div style="color:var(--muted); font-size:13px; text-align:center; padding:16px; background:#0b1120; border-radius:10px; border:1px dashed var(--border);">No hay ningún jugador conectado ahora mismo.</div>';
    }

    // Datalist of known players for autocompletion
    const dl = document.getElementById('knownPlayersList');
    if (data.knownPlayers) {
      dl.innerHTML = data.knownPlayers.map(k => '<option value="' + k + '">').join('');
    }

    // Active warnings list
    const wContainer = document.getElementById('warnedListContainer');
    if (wContainer) {
      const allWarns = data.warnings || {};
      const warnedEntries = Object.entries(allWarns).filter(([p, list]) => list && list.length > 0);
      if (warnedEntries.length > 0) {
        wContainer.innerHTML = warnedEntries.map(([player, list]) => 
          '<div style="background:#0b1120; border:1px solid #78350f; border-radius:10px; padding:10px 14px; margin-bottom:6px; display:flex; justify-content:space-between; align-items:center;">'
          + '<div>'
          + '<div style="display:flex; align-items:center; gap:6px;">'
          + '<span style="font-weight:700; color:#fbbf24; font-size:13px;">⚠️ ' + player + '</span>'
          + '<span class="pill-state pill-off" style="font-size:10px;">' + list.length + '/4 ADVERTENCIAS</span>'
          + '</div>'
          + '<div style="font-size:11px; color:var(--muted); margin-top:4px;">'
          + list.map((m, idx) => '<div>' + (idx + 1) + '. ' + m + '</div>').join('')
          + '</div>'
          + '</div>'
          + '<div class="btn-row">'
          + '<button class="btn-amber btn-sm" onclick="quickWarnUser(\'' + player + '\')">+1 Warn</button>'
          + '<button class="btn-dark btn-sm" onclick="quickUnwarn(\'' + player + '\')">-1 Warn</button>'
          + '<button class="btn-green btn-sm" onclick="quickClearWarns(\'' + player + '\')">Limpiar</button>'
          + '</div>'
          + '</div>'
        ).join('');
      } else {
        wContainer.innerHTML = '<div style="color:var(--muted); font-size:12px; background:#0b1120; border-radius:8px; padding:10px 14px; border:1px dashed var(--border);">No hay jugadores con advertencias activas (todos limpios 0/4).</div>';
      }
    }

    // Render Alliances
    var alContainer = document.getElementById('alliancesContainer');
    if (alContainer && data.alliances) {
      var allAl = Object.values(data.alliances);
      if (allAl.length > 0) {
        alContainer.innerHTML = allAl.map(function(al) {
          var isEnemy = al.enemies && al.enemies.length > 0;
          var ffBg = al.friendlyFire ? '#7f1d1d; color:#fca5a5' : '#064e3b; color:#6ee7b7';
          var ffText = al.friendlyFire ? '🔥 Fuego Amigo ON' : '🛡️ Fuego Amigo OFF';
          var enemyHtml = isEnemy
            ? '<div style="font-size:11px; color:#ef4444; background:#450a0a; padding:4px 8px; border-radius:6px; border:1px solid #7f1d1d;">⚔️ En Guerra con: ' + al.enemies.join(', ') + '</div>'
            : '<div style="font-size:11px; color:#10b981;">🕊️ En Paz (Sin guerras)</div>';
          var memCount = al.members ? al.members.length : 0;
          var memList = (al.members || []).join(', ');
          return '<div style="background:#0b1120; border:1px solid var(--border); border-radius:12px; padding:14px;">'
            + '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">'
            + '<span style="font-size:15px; font-weight:800; color:#38bdf8;">[' + al.name + ']</span>'
            + '<span style="font-size:11px; padding:2px 8px; border-radius:6px; font-weight:700; background:' + ffBg + ';">' + ffText + '</span>'
            + '</div>'
            + '<div style="font-size:12px; color:var(--muted); margin-bottom:4px;">👑 Líder: <span style="color:#fbbf24; font-weight:700;">' + al.leader + '</span></div>'
            + '<div style="font-size:12px; color:var(--muted); margin-bottom:8px;">👥 Miembros (' + memCount + '): <span style="color:#f1f5f9;">' + memList + '</span></div>'
            + '<div style="font-size:12px; color:var(--muted); margin-bottom:8px;">⚔️ Bajas PvP: <span style="color:#f87171; font-weight:700;">' + (al.kills || 0) + '</span></div>'
            + enemyHtml
            + '</div>';
        }).join('');
      } else {
        alContainer.innerHTML = '<div style="grid-column:1/-1; color:var(--muted); font-size:13px; text-align:center; padding:16px; background:#0b1120; border-radius:10px; border:1px dashed var(--border);">No hay ninguna alianza creada todavía. Usa /alianza crear &lt;nombre&gt; dentro de Minecraft para fundar tu propio clan.</div>';
      }
    }

    // Render Honor RDR2
    var hContainer = document.getElementById('honorListContainer');
    if (hContainer) {
      var allHonor = data.honor || {};
      var known = data.knownPlayers || [];
      var playersToRender = Array.from(new Set(known.concat(Object.keys(allHonor))));
      if (playersToRender.length > 0) {
        hContainer.innerHTML = playersToRender.map(function(pName) {
          var pLower = pName.toLowerCase();
          var score = (allHonor[pLower] !== undefined) ? allHonor[pLower] : 0;
          var title = '⚖️ Neutral';
          var titleColor = '#94a3b8';
          if (score >= 80) { title = '🌟 Héroe Legendario'; titleColor = '#f59e0b'; }
          else if (score >= 40) { title = '✨ Forastero Justo'; titleColor = '#eab308'; }
          else if (score >= 10) { title = '🤠 Buen Samaritano'; titleColor = '#10b981'; }
          else if (score > -10) { title = '⚖️ Neutral'; titleColor = '#94a3b8'; }
          else if (score > -40) { title = '🗡️ Buscavidas Despiadado'; titleColor = '#f87171'; }
          else if (score > -80) { title = '🔥 Forajido Peligroso'; titleColor = '#dc2626'; }
          else { title = '☠️ Enemigo Público (Buscado)'; titleColor = '#991b1b'; }

          var pct = Math.max(0, Math.min(100, Math.round(((score + 100) / 200) * 100)));
          var isXavi = pLower === 'xavi';
          var nameCol = isXavi ? '#fbbf24' : '#fff';
          var scoreCol = score >= 0 ? '#34d399' : '#f87171';
          var scoreTxt = (score >= 0 ? '+' + score : score) + ' Honor';

          return '<div style="background:#0b1120; border:1px solid var(--border); border-radius:12px; padding:12px 16px;">'
            + '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">'
            + '<div style="display:flex; align-items:center; gap:8px;">'
            + '<span style="font-weight:800; font-size:14px; color:' + nameCol + ';">' + pName + '</span>'
            + '<span style="font-size:11px; font-weight:700; color:' + titleColor + ';">' + title + '</span>'
            + '</div>'
            + '<span style="font-weight:800; font-size:14px; color:' + scoreCol + ';">' + scoreTxt + '</span>'
            + '</div>'
            + '<div style="display:flex; align-items:center; gap:10px;">'
            + '<span style="font-size:14px;">💀</span>'
            + '<div style="flex:1; height:10px; background:#1e293b; border-radius:5px; position:relative; overflow:visible;">'
            + '<div style="position:absolute; left:0; width:50%; height:100%; border-right:2px solid #64748b; background:linear-gradient(to right, #ef4444, #64748b); border-top-left-radius:5px; border-bottom-left-radius:5px; opacity:0.3;"></div>'
            + '<div style="position:absolute; left:50%; width:50%; height:100%; background:linear-gradient(to right, #64748b, #10b981); border-top-right-radius:5px; border-bottom-right-radius:5px; opacity:0.3;"></div>'
            + '<div style="position:absolute; top:-3px; left:calc(' + pct + '% - 8px); width:16px; height:16px; border-radius:50%; background:#fff; box-shadow:0 0 8px #fff; border:2px solid ' + titleColor + ';"></div>'
            + '</div>'
            + '<span style="font-size:14px;">🌟</span>'
            + '</div>'
            + '</div>';
        }).join('');
      } else {
        hContainer.innerHTML = '<div style="color:var(--muted); font-size:13px; text-align:center; padding:16px;">No hay datos de honor disponibles aún.</div>';
      }
    }

    // Banned players list
    const bContainer = document.getElementById('bannedListContainer');
    if (data.banned && data.banned.length > 0) {
      bContainer.innerHTML = data.banned.map(b => 
        '<div style="background:#0b1120; border:1px solid #e11d48; border-radius:10px; padding:10px 14px; display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">'
        + '<div>'
        + '<div style="font-weight:700; color:#fb7185; font-size:13px;">🚫 ' + b.name + '</div>'
        + '<div style="font-size:11px; color:var(--muted); margin-top:2px;">Motivo: ' + (b.reason || 'Sin motivo especificado') + '</div>'
        + '</div>'
        + '<button class="btn-green btn-sm" onclick="quickUnban(\'' + b.name + '\')">✅ Desbanear</button>'
        + '</div>'
      ).join('');
    } else {
      bContainer.innerHTML = '<div style="color:#34d399; font-size:12px; background:#0b1120; padding:10px 14px; border-radius:8px; border:1px solid var(--border);">✅ No hay ningún jugador baneado actualmente.</div>';
    }

    // Log Console
    const logBox = document.getElementById('logBox');
    const wasBottom = logBox.scrollTop + logBox.clientHeight >= logBox.scrollHeight - 25;
    logBox.textContent = data.logs || 'Sin registros.';
    if (wasBottom) logBox.scrollTop = logBox.scrollHeight;

  } catch (e) {}
}

function quickBanUser(p) {
  if (confirm('¿Seguro que quieres banear a ' + p + '?')) {
    runCmd('ban ' + p + ' Baneado desde la consola');
  }
}

refreshState();
setInterval(refreshState, 2500);
</script>
</body>
</html>`;

function createWorldBackup() {
  const dateStr = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const backupFolder = path.join(SERVER_DIR, 'backups', `mundo_${dateStr}`);
  fs.mkdirSync(backupFolder, { recursive: true });
  const src = path.join(SERVER_DIR, 'server', 'world');
  fs.cpSync(src, backupFolder, { recursive: true });
  return `Copia creada con éxito en backups/mundo_${dateStr}`;
}

function restoreOriginalWorld() {
  const originalBackup = path.join(SERVER_DIR, 'server', 'world_backup_original');
  const worldDir = path.join(SERVER_DIR, 'server', 'world');
  if (!fs.existsSync(originalBackup)) {
    throw new Error('No se encontró server/world_backup_original');
  }
  fs.rmSync(worldDir, { recursive: true, force: true });
  fs.cpSync(originalBackup, worldDir, { recursive: true });
  return 'Mundo restaurado con éxito a su estado original de New World (8).epk';
}


let idleSecondsCounter = 0;
let autoShutdownMinutes = 10;
let autoShutdownActive = true;

setInterval(async () => {
  if (!autoShutdownActive || autoShutdownMinutes <= 0) {
    idleSecondsCounter = 0;
    return;
  }
  try {
    const listOut = await sendRconCommand('list', 1500);
    const parts = listOut.split(':');
    let playersCount = 0;
    if (parts.length > 1 && parts[1].trim()) {
      playersCount = parts[1].split(',').map(x => x.trim()).filter(Boolean).length;
    }
    if (playersCount === 0) {
      idleSecondsCounter += 5;
      if (idleSecondsCounter >= autoShutdownMinutes * 60) {
        console.log('================================================================');
        console.log(` [AUTO-APAGADO] Servidor sin jugadores durante ${autoShutdownMinutes} minutos.`);
        console.log(' [AUTO-APAGADO] Guardando mundo y apagando todo para liberar el ordenador de Xavi...');
        console.log('================================================================');
        await safeShutdown();
        process.exit(0);
      }
    } else {
      idleSecondsCounter = 0;
    }
  } catch (e) {
    idleSecondsCounter = 0;
  }
}, 5000);


function startMinecraftServer() {
  const batPath = path.join(SERVER_DIR, 'iniciar_minecraft.bat');
  spawn('cmd.exe', ['/c', batPath], { detached: true, stdio: 'ignore' }).unref();
}

function startBungeeProxy() {
  // Handled together in iniciar_minecraft.bat
}

function startPanelServer() {
  const srv = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');

    const reqUrl = (req.url || '/').split('?')[0];

    if (req.method === 'GET' && (reqUrl === '/' || reqUrl === '/index.html')) {
      const htmlFile = path.join(SERVER_DIR, 'admin_panel.html');
      const htmlContent = fs.existsSync(htmlFile) ? fs.readFileSync(htmlFile, 'utf8') : HTML_PAGE;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(htmlContent);
      return;
    }
    if (req.method === 'GET' && (reqUrl === '/client' || reqUrl === '/client.html')) {
      const clientFile = path.join(SERVER_DIR, 'EaglercraftX_1.8_Online_Fix.html');
      if (fs.existsSync(clientFile)) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(fs.readFileSync(clientFile));
        return;
      }
    }
    if (req.method === 'POST' && reqUrl === '/api/chat') {
      let body = '';
      req.on('data', chunk => body += chunk);
      req.on('end', async () => {
        try {
          const { message } = JSON.parse(body || '{}');
          const clean = (message || '').trim();
          if (clean) {
            const now = new Date();
            const timeStr = String(now.getHours()).padStart(2, '0') + ':' +
                            String(now.getMinutes()).padStart(2, '0') + ':' +
                            String(now.getSeconds()).padStart(2, '0');
            adminChatHistory.push({
              time: timeStr,
              user: 'Xavi [Admin]',
              text: clean,
              type: 'admin'
            });
            if (adminChatHistory.length > 50) adminChatHistory.shift();

            const safeMsg = clean.replace(/"/g, '\\"');
            await sendRconCommand(`tellraw @a [{"text":"[Admin] Xavi: ","color":"gold","bold":true},{"text":"${safeMsg}","color":"white"}]`);
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true }));
        } catch (err) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: err.message }));
        }
      });
      return;
    }
    if (req.method === 'GET' && reqUrl === '/api/state') {
      const state = getServerState();
      let online = false;
      let players = [];
      try {
        const listOut = await sendRconCommand('list', 1000);
        online = true;
        const parts = listOut.split(':');
        if (parts.length > 1 && parts[1].trim()) {
          players = parts[1].split(',').map(s => s.trim()).filter(Boolean);
        }
      } catch (e) {
        online = false;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        online,
        players,
        idleSeconds: idleSecondsCounter,
        autoShutdownEnabled: autoShutdownActive,
        modo: currentMode,
        tunnelStatus,
        tunnelUrl: {
          web: publicWebUrl,
          ws: publicWsUrl
        },
        ...state
      }));
      return;
    }
    if (req.method === 'POST' && reqUrl === '/api/switch-mode') {
      let body = '';
      req.on('data', chunk => body += chunk);
      req.on('end', () => {
        try {
          const { modo } = JSON.parse(body);
          if (modo === 'casa') {
            startCloudflareTunnel();
          } else if (modo === 'ngrok') {
            startNgrokTunnel();
          } else {
            stopTunnel();
            currentMode = 'colegio';
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, modo: currentMode }));
        } catch (err) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: err.message }));
        }
      });
      return;
    }
    if (req.method === 'POST' && reqUrl === '/api/toggle-autoshutdown') {
      autoShutdownActive = !autoShutdownActive;
      idleSecondsCounter = 0;
      try {
        await sendRconCommand(autoShutdownActive ? 'autoapagado 10' : 'autoapagado off');
      } catch (ignored) {}
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, autoShutdownActive }));
      return;
    }
    if (req.method === 'POST' && reqUrl === '/api/cmd') {
      let body = '';
      req.on('data', chunk => body += chunk);
      req.on('end', async () => {
        try {
          const { cmd } = JSON.parse(body);
          const cleanCmd = cmd.startsWith('/') ? cmd.slice(1) : cmd;
          const output = await sendRconCommand(cleanCmd);
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, output }));
        } catch (err) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, output: 'Error: ' + err.message }));
        }
      });
      return;
    }
    if (req.method === 'POST' && reqUrl === '/api/backup') {
      try {
        const msg = createWorldBackup();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, message: msg }));
      } catch (err) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, message: err.message }));
      }
      return;
    }
    if (req.method === 'POST' && reqUrl === '/api/restore') {
      try {
        const msg = restoreOriginalWorld();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, message: msg }));
      } catch (err) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, message: err.message }));
      }
      return;
    }
    if (req.method === 'POST' && reqUrl === '/api/start') {
      try {
        startMinecraftServer();
        startBungeeProxy();
        if (currentMode === 'casa' && !tunnelProcess) {
          startCloudflareTunnel();
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, message: 'Iniciando servidor de Minecraft y Proxy...' }));
      } catch (err) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, message: err.message }));
      }
      return;
    }
    if (req.method === 'POST' && reqUrl === '/api/stop') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, message: 'Apagando servidor y liberando puertos...' }));
      await safeShutdown();
      setTimeout(() => {
        try { srv.close(); } catch (e) {}
        process.exit(0);
      }, 500);
      return;
    }
    res.writeHead(404);
    res.end();
  });

  srv.listen(PANEL_PORT, '0.0.0.0', () => {
    console.log('================================================================');
    console.log(` 🛡️  PANEL DE CONTROL ACTIVO EN: http://localhost:${PANEL_PORT}`);
    console.log('================================================================');
    try {
      spawn('cmd.exe', ['/c', 'start', `http://localhost:${PANEL_PORT}`], { detached: true, stdio: 'ignore' }).unref();
    } catch (e) {}
    startCliLoop();
  });
}

function printCliDashboard() {
  const state = getServerState();
  const rules = state.rules;
  console.log('\n╔═══════════════════════════════════════════════════════════════════════╗');
  console.log('║        🛡️  CONSOLA DE CONTROL TOTAL — SERVIDOR DE XAVI                ║');
  console.log('╚═══════════════════════════════════════════════════════════════════════╝');
  console.log(' [ESTADO EN VIVO]');
  console.log(`  • Modo de Conexión:    ${currentMode === 'casa' ? '🏠 MODO CASA (Internet Público - Cloudflare)' : (currentMode === 'ngrok' ? '🌐 MODO CASA (Ngrok)' : '🏫 MODO COLEGIO (Punto de acceso / Hotspot)')}`);
  if (currentMode === 'casa' || currentMode === 'ngrok') {
    console.log(`  • Enlace Web:          ${publicWebUrl || '(Generando enlace...)'}`);
    console.log(`  • Dirección Amigos:    ${publicWsUrl || '(Generando dirección wss://...)'}`);
  } else {
    console.log(`  • Dirección Amigos:    ws://192.168.137.1:8081`);
  }
  console.log(`  • Auto-Ban en Casa:    [ ${rules.autoBan ? 'ACTIVADO (Verde)' : 'SOLO BLOQUEAR'} ]`);
  console.log(`  • Cofres y Hornos:     [ ${rules.protectChests ? 'BLOQUEADOS (Solo Xavi)' : 'ABIERTOS'} ]`);
  console.log(`  • Anti-Explosiones:    [ ${rules.antiExplosion ? 'SEGURO (Sin daño)' : 'DESTRUCTORAS'} ]`);
  console.log(`  • Ubicación Casa:      X=${rules.zone.x}, Y=${rules.zone.y}, Z=${rules.zone.z} (Radio: ${rules.zone.radius})`);
  console.log(`  • Amigos con permiso:  ${rules.friends.length ? rules.friends.join(', ') : 'Ninguno (Solo Xavi)'}`);
  console.log(`  • Jugadores baneados:  ${state.banned.length ? state.banned.map(b => b.name).join(', ') : 'Ninguno'}`);
  console.log('───────────────────────────────────────────────────────────────────────');
  console.log(' [ACCIONES RÁPIDAS - Escribe el número o cualquier comando]:');
  console.log('  [1] Ver Estado y Jugadores Online     [6] Restaurar Inventario del EPK');
  console.log('  [2] Banear Jugador                   [7] Día y Sol Despejado');
  console.log('  [3] Desbanear Jugador                [8] Modo Vuelo / Inmortal (God)');
  console.log('  [4] Alternar Auto-Ban en Casa        [9] Guardar Mundo en Disco');
  console.log('  [5] Alternar Bloqueo de Cofres       [0] Guardar y Apagar Servidor');
  console.log('───────────────────────────────────────────────────────────────────────');
  console.log(` Panel Visual Web abierto en: http://localhost:${PANEL_PORT}\n`);
}

function startCliLoop() {
  if (!process.stdin.isTTY) {
    return;
  }
  const readline = require('readline');
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  globalRl = rl;
  printCliDashboard();
  rl.setPrompt('Xavi Admin > ');
  rl.prompt();
  
  rl.on('line', async (line) => {
    const input = line.trim();
    if (!input) { rl.prompt(); return; }

    if (input === '0' || input.toLowerCase() === 'stop' || input.toLowerCase() === 'salir') {
      await safeShutdown();
      setTimeout(() => {
        try { srv.close(); } catch (e) {}
        process.exit(0);
      }, 500);
      return;
    } else if (input === '1') {
      try {
        const out = await sendRconCommand('list');
        console.log('➜ ' + out);
        const casa = await sendRconCommand('infocasa');
        console.log('➜ ' + casa);
      } catch (e) { console.log('Servidor apagado o no responde.'); }
    } else if (input === '2') {
      rl.question('Nombre del jugador a banear: ', async (target) => {
        if (target.trim()) {
          const out = await sendRconCommand('ban ' + target.trim() + ' Baneado desde consola');
          console.log('➜ ' + out);
        }
        rl.prompt();
      });
      return;
    } else if (input === '3') {
      rl.question('Nombre del jugador a desbanear: ', async (target) => {
        if (target.trim()) {
          const out = await sendRconCommand('unban ' + target.trim());
          console.log('➜ ' + out);
        }
        rl.prompt();
      });
      return;
    } else if (input.toLowerCase() === 'w') {
      rl.question('Nombre del jugador a advertir: ', (target) => {
        if (!target.trim()) { rl.prompt(); return; }
        rl.question('Motivo de la advertencia: ', async (motivo) => {
          const m = motivo.trim() || 'Comportamiento inadecuado';
          const out = await sendRconCommand('warn ' + target.trim() + ' ' + m);
          console.log('➜ ' + out);
          rl.prompt();
        });
      });
      return;
    } else if (input.toLowerCase() === 'v') {
      const out = await sendRconCommand('warns');
      console.log('➜ ' + out);
    } else if (input === '4') {
      const out = await sendRconCommand('autobancasa');
      console.log('➜ ' + out);
    } else if (input === '5') {
      const out = await sendRconCommand('cofrescasa');
      console.log('➜ ' + out);
    } else if (input === '6') {
      const out = await sendRconCommand('recuperarinventario');
      console.log('➜ ' + out);
    } else if (input === '7') {
      await sendRconCommand('time set day');
      const out = await sendRconCommand('weather clear');
      console.log('➜ Sol y día despejado activado.');
    } else if (input === '8') {
      const out = await sendRconCommand('god Xavi');
      console.log('➜ ' + out);
    } else if (input === '9') {
      const out = await sendRconCommand('guardar');
      console.log('➜ ' + out);
    } else {
      try {
        const clean = input.startsWith('/') ? input.slice(1) : input;
        const out = await sendRconCommand(clean);
        console.log('➜ ' + (out || '(Comando ejecutado)'));
      } catch (e) {
        console.log('Error: ' + e.message);
      }
    }
    rl.prompt();
  });
}

const args = process.argv.slice(2);
let action = 'panel';

for (let i = 0; i < args.length; i++) {
  const a = args[i].toLowerCase();
  if (a === 'stop') action = 'stop';
  else if (a === 'cmd') action = 'cmd';
  else if (a === 'casa' || a === '--casa' || a === 'home') currentMode = 'casa';
  else if (a === 'colegio' || a === '--colegio' || a === 'school') currentMode = 'colegio';
  else if (a === 'ngrok' || a === '--ngrok') currentMode = 'ngrok';
}

if (action === 'stop') {
  safeShutdown().then(() => process.exit(0));
} else if (action === 'cmd') {
  const cmd = process.argv.slice(3).join(' ');
  sendRconCommand(cmd).then(out => {
    console.log(out);
    process.exit(0);
  }).catch(err => {
    console.error(err.message);
    process.exit(1);
  });
} else {
  startPanelServer();
  if (currentMode === 'casa') {
    startCloudflareTunnel();
  } else if (currentMode === 'ngrok') {
    startNgrokTunnel();
  }
}
