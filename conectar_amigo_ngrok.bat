@echo off
title Conectar Amigo por Ngrok (Puerto 8081)
cd /d "%~dp0"
cls
echo ==============================================================
echo        ABRIENDO TUNEL SEGURO PARA TU AMIGO (NGROK)
echo ==============================================================
echo.
echo [IMPORTANTE 1]: Esta ventana DEBE permanecer abierta mientras jugais.
echo                Si la cierras, el servidor se desconecta de internet.
echo.
echo [IMPORTANTE 2 - EVITAR HANDSHAKE TIMED OUT]:
echo  Tu amigo debe abrir primero en una pestana de Edge o Chrome:
echo    https://TU-SUBDOMINIO.ngrok-free.app
echo  Y hacer clic en el boton azul "Visit Site" UNA SOLA VEZ.
echo.
echo [IMPORTANTE 3]:
echo  En Eaglercraft, tu amigo debe poner la direccion con wss:// y SIN barra final:
echo    wss://TU-SUBDOMINIO.ngrok-free.app
echo.
echo ==============================================================
echo Iniciando tunel en el puerto 8081...
echo Copia la direccion que aparezca en "Forwarding: https://...."
echo ==============================================================
echo.
ngrok http 8081
pause
