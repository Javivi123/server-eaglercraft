@echo off
title Servidor Eaglercraft de Xavi - Selector de Modo
cd /d "%~dp0"
cls

echo ========================================================================
echo       BIENVENIDO AL SERVIDOR EAGLERCRAFT DE XAVI (NEW WORLD 8)
echo ========================================================================
echo.
echo  Selecciona como quieres iniciar el servidor hoy:
echo.
echo  [1] MODO COLEGIO (Punto de Acceso / Zona con Cobertura)
echo      - Para jugar en el instituto cuando estais todos juntos.
echo      - Usa la IP local del Hotspot: ws://192.168.137.1:8081
echo.
echo  [2] MODO CASA (Internet Publico con Cloudflare - Recomendado en casa)
echo      - Para que tus amigos jueguen desde sus casas con cualquier WiFi.
echo      - Usa los tuneles trycloudflare.com que SI saltan la VPN del colegio.
echo      - Auto-sincroniza con Entrar_Servidor_Xavi.html y con wss://xavi.
echo.
echo  [3] MODO CASA OPCION 2 (Ngrok alternativo por si la VPN bloquea)
echo.
echo  [0] Salir
echo.
echo ========================================================================
set /p modo="Elige una opcion (1, 2 o 3): "

if "%modo%"=="1" goto colegio
if "%modo%"=="2" goto casa
if "%modo%"=="3" goto ngrok
if "%modo%"=="0" exit
echo Opcion no valida.
pause
exit

:colegio
call "%~dp0iniciar_MODO_COLEGIO.bat"
exit

:casa
call "%~dp0iniciar_MODO_CASA.bat"
exit

:ngrok
call "%~dp0iniciar_MODO_CASA_OPCION2_NGROK.bat"
exit
