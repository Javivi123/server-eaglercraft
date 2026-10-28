@echo off
title Crear Copia de Seguridad del Mundo
cd /d "%~dp0"
cls
echo ==============================================================
echo             CREAR COPIA DE SEGURIDAD DEL MUNDO
echo ==============================================================
echo.
echo Forzando autoguardado en el servidor antes de copiar...
node "%~dp0admin_controller.js" cmd guardar 2>nul
timeout /t 2 /nobreak > nul

set FECHA=%DATE:/=-%_%TIME::=-%
set FECHA=%FECHA: =_%
set DEST=backups\mundo_%FECHA%

echo Guardando copia actual en: %DEST%...
if not exist "backups" mkdir "backups"
xcopy /e /i /y "server\world" "%DEST%" >nul

echo.
echo ==============================================================
echo  COPIA GUARDADA CON EXITO!
echo ==============================================================
echo.
pause
