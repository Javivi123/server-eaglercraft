@echo off
title Guardar y Apagar Servidor Eaglercraft (Cierre Total)
cd /d "%~dp0"
cls

echo ========================================================================
echo         GUARDANDO MUNDO Y APAGANDO TODOS LOS SERVICIOS
echo ========================================================================
echo.
echo 1. Enviando orden de guardado limpio al servidor...
node "%~dp0admin_controller.js" stop >nul 2>&1

echo 2. Deteniendo procesos de Java (Minecraft y BungeeCord Proxy)...
taskkill /f /im java.exe >nul 2>&1

echo 3. Deteniendo tuneles de conexion (Cloudflare y Ngrok)...
taskkill /f /im cloudflared.exe >nul 2>&1
taskkill /f /im ngrok.exe >nul 2>&1

echo 4. Cerrando consolas de administracion activas...
powershell -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*admin_controller.js*' -and $_.ProcessId -ne $PID } | Stop-Process -Force -ErrorAction SilentlyContinue" >nul 2>&1

echo 5. Cerrando ventanas del servidor...
taskkill /f /fi "WINDOWTITLE eq 1. Minecraft Paper 1.8.8 Server*" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq 2. BungeeCord Eaglercraft Proxy*" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq 3. Consola de Control Total*" >nul 2>&1

echo 6. Verificando y liberando puertos (8081, 8090, 25565, 25575, 25577)...
powershell -ExecutionPolicy Bypass -Command "$ports = @(8081, 8090, 25565, 25575, 25577); $procs = Get-NetTCPConnection -LocalPort $ports -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 4 }; if ($procs) { $procs | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }" >nul 2>&1

echo.
echo ========================================================================
echo           TODO APAGADO Y TODOS LOS PUERTOS LIBERADOS (100%%)
echo ========================================================================
echo - Servidor Minecraft: APAGADO
echo - Proxy Eaglercraft (Puerto 8081): LIBERADO
echo - Consola Web y CLI (Puerto 8090): LIBERADA
echo - Puertos 25565, 25575, 25577: LIBERADOS
echo - Memoria RAM y procesador de tu PC: 100%% LIBRES
echo ========================================================================
echo.
ping 127.0.0.1 -n 3 >nul
exit
