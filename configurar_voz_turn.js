const fs = require('fs');
const path = require('path');
const readline = require('readline');

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout
});

const ICE_PATH = path.join(__dirname, 'bungee', 'plugins', 'EaglercraftXBungee', 'ice_servers.yml');
const CONFIG_FILE = path.join(__dirname, 'voice_turn_config.json');

console.log('========================================================================');
console.log('   CONFIGURADOR DE CHAT DE VOZ (TURN / STUN) - SERVIDOR DE XAVI');
console.log('========================================================================');
console.log('');
console.log('Elige que servicio de Relay TURN quieres utilizar:');
console.log('');
console.log('  [1] CLOUDFLARE CALLS TURN (Recomendado: 1.000 GB / 1 TB gratis al mes)');
console.log('      - Es de Cloudflare (tu misma cuenta), ilimitado para jugar.');
console.log('      - Entra en tu panel de Cloudflare -> Calls / Realtime -> TURN Keys.');
console.log('      - Creas una Key y copias el "Key ID" y el "API Token".');
console.log('');
console.log('  [2] METERED.CA (500 MB gratis al mes - Para probar rapido hoy)');
console.log('      - Da para unas 8-10 horas de llamada grupal.');
console.log('      - Introduces tu "App Name" y "API Key" de Metered.');
console.log('');
console.log('  [3] Servidor TURN propio / manual (URL, usuario y contraseña)');
console.log('  [4] Solo STUN publico (Google, Cloudflare, Nextcloud)');
console.log('  [0] Salir');
console.log('========================================================================');

rl.question('Elige una opcion (1, 2, 3, 4 o 0): ', async (opc) => {
  if (opc === '1') {
    rl.question('Introduce tu Cloudflare TURN Key ID: ', (keyId) => {
      rl.question('Introduce tu Cloudflare API Token (Bearer Token): ', async (apiToken) => {
        keyId = keyId.trim();
        apiToken = apiToken.trim();
        if (!keyId || !apiToken) {
          console.log('Datos invalidos.');
          rl.close();
          return;
        }
        console.log('\nGenerando credenciales TURN en Cloudflare Calls...');
        try {
          const res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${keyId}/credentials/generate-ice-servers`, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${apiToken}`,
              'Content-Type': 'application/json'
            },
            body: JSON.stringify({ ttl: 86400 })
          });
          const data = await res.json();
          if (!data.iceServers) {
            console.error('Error de Cloudflare:', data);
            rl.close();
            return;
          }

          let noPasswd = [
            'stun:stun.cloudflare.com:3478',
            'stun:stun.nextcloud.com:443',
            'stun:stun.l.google.com:19302'
          ];
          let passwdEntries = [];
          let count = 1;

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

          let yaml = 'voice_servers_no_passwd:\n';
          for (const s of noPasswd) yaml += `- '${s}'\n`;
          yaml += 'voice_servers_passwd:\n' + (passwdEntries.length > 0 ? passwdEntries.join('\n') + '\n' : '  {}\n');
          fs.writeFileSync(ICE_PATH, yaml, 'utf8');

          fs.writeFileSync(CONFIG_FILE, JSON.stringify({
            provider: 'cloudflare',
            keyId,
            apiToken,
            updated: new Date().toISOString()
          }, null, 2));

          console.log('\n========================================================================');
          console.log(' ¡CHAT DE VOZ CONFIGURADO CON CLOUDFLARE CALLS (1.000 GB AL MES)!');
          console.log(' Servidor configurado: turn.cloudflare.com por puerto 5349/443 TCP y 3478.');
          console.log(' Las credenciales se renovaran automaticamente cada 12 horas.');
          console.log('========================================================================\n');
        } catch (err) {
          console.error('Error al conectar con Cloudflare Calls:', err.message);
        }
        rl.close();
      });
    });
  } else if (opc === '2') {
    rl.question('Introduce tu App Name de Metered (ej: mi-servidor): ', (appName) => {
      rl.question('Introduce tu API Key de Metered: ', async (apiKey) => {
        appName = appName.trim().replace('.metered.live', '').replace('https://', '').replace('/', '');
        apiKey = apiKey.trim();
        if (!appName || !apiKey) {
          console.log('Datos invalidos.');
          rl.close();
          return;
        }
        console.log('\nConsultando servidores TURN en Metered.ca...');
        try {
          const res = await fetch(`https://${appName}.metered.live/api/v1/turn/credentials?apiKey=${apiKey}`);
          const data = await res.json();
          if (!Array.isArray(data)) {
            console.error('Error de Metered:', data);
            rl.close();
            return;
          }

          let noPasswd = [
            'stun:stun.nextcloud.com:443',
            'stun:stun.cloudflare.com:3478',
            'stun:stun.l.google.com:19302'
          ];
          let passwdEntries = [];
          let count = 1;

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

          let yaml = 'voice_servers_no_passwd:\n';
          for (const s of noPasswd) yaml += `- '${s}'\n`;
          yaml += 'voice_servers_passwd:\n' + (passwdEntries.length > 0 ? passwdEntries.join('\n') + '\n' : '  {}\n');
          fs.writeFileSync(ICE_PATH, yaml, 'utf8');

          fs.writeFileSync(CONFIG_FILE, JSON.stringify({
            provider: 'metered',
            appName,
            apiKey,
            updated: new Date().toISOString()
          }, null, 2));

          console.log('\n========================================================================');
          console.log(' ¡CHAT DE VOZ CONFIGURADO CON METERED.CA!');
          console.log(' Puertos: 80, 443 TCP y UDP.');
          console.log('========================================================================\n');
        } catch (err) {
          console.error('Error con Metered:', err.message);
        }
        rl.close();
      });
    });
  } else if (opc === '3') {
    rl.question('URL del servidor TURN (ej: turn:mi-turn.com:443?transport=tcp): ', (turnUrl) => {
      rl.question('Usuario: ', (user) => {
        rl.question('Contraseña: ', (pass) => {
          let yaml = `voice_servers_no_passwd:
- 'stun:stun.nextcloud.com:443'
- 'stun:stun.cloudflare.com:3478'
- 'stun:stun.l.google.com:19302'
voice_servers_passwd:
  personal1:
    url: '${turnUrl.trim()}'
    username: '${user.trim()}'
    password: '${pass.trim()}'
`;
          fs.writeFileSync(ICE_PATH, yaml, 'utf8');
          console.log('\n¡Servidor TURN guardado en ice_servers.yml!');
          rl.close();
        });
      });
    });
  } else if (opc === '4') {
    let yaml = `voice_servers_no_passwd:
- 'stun:stun.nextcloud.com:443'
- 'stun:stun.cloudflare.com:3478'
- 'stun:stun.l.google.com:19302'
- 'stun:stun1.l.google.com:19302'
- 'stun:stun2.l.google.com:19302'
- 'stun:stun.sipgate.net:3478'
voice_servers_passwd: {}
`;
    fs.writeFileSync(ICE_PATH, yaml, 'utf8');
    console.log('\nServidores STUN guardados en ice_servers.yml.');
    rl.close();
  } else {
    console.log('Saliendo sin cambios.');
    rl.close();
  }
});
