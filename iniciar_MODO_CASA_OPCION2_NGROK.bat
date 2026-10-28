@echo off
title Servidor Eaglercraft de Xavi [MODO CASA - OPCION 2 NGROK]
cd /d "%~dp0"
cls

echo ========================================================================
echo     INICIANDO SERVIDOR EAGLERCRAFT DE XAVI [MODO CASA - NGROK]
echo ========================================================================
echo.
echo [INFO]: Usa esta opcion alternativa si la VPN del colegio bloquea
echo         el enlace de Cloudflare a algun amigo.
echo.
echo 0. Comprobando y liberando puertos anteriores por seguridad...
powershell -ExecutionPolicy Bypass -Command "$ports = @(8081, 8090, 25565, 25575, 25577); $procs = Get-NetTCPConnection -LocalPort $ports -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 4 }; if ($procs) { $procs | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }" >nul 2>&1
taskkill /f /im cloudflared.exe >nul 2>&1
taskkill /f /im ngrok.exe >nul 2>&1
echo.
echo 1. Iniciando servidor Paper 1.8.8 (Mundo de New World 8)...
start "1. Minecraft Paper 1.8.8 Server" cmd /k "cd /d ""%~dp0server"" && ""%~dp0jre\bin\java.exe"" -Xmx1024M -Xms512M -jar server.jar nogui"

echo 2. Esperando 6 segundos a que cargue el mundo...
ping 127.0.0.1 -n 7 >nul

echo 3. Iniciando BungeeCord Eaglercraft Proxy (Puerto 8081)...
start "2. BungeeCord Eaglercraft Proxy" cmd /k "cd /d ""%~dp0bungee"" && ""%~dp0jre\bin\java.exe"" -Deaglerxbungee.stfu=true -Xmx512M -Xms256M -jar bungee.jar"

echo 4. Abriendo Tunel Ngrok y Consola de Control de Xavi...
start "3. Consola de Control Total - Xavi [MODO NGROK]" cmd /k "cd /d ""%~dp0"" && node admin_controller.js ngrok"

echo.
echo ========================================================================
echo                 TODO INICIADO CORRECTAMENTE
echo ========================================================================
echo.
echo - La consola se esta abriendo y generara la URL de Ngrok.
echo - Importante: Tus amigos deben abrir primero el enlace en Chrome/Edge
echo   y hacer clic en "Visit Site" una sola vez.
echo.
pause
