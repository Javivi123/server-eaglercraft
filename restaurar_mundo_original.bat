@echo off
title Restaurar Mundo Original
cd /d "%~dp0"
cls
echo ==============================================================
echo             RESTAURAR MUNDO A SU ESTADO ORIGINAL
echo ==============================================================
echo.
echo Esta herramienta borrara cualquier destrozo o cambio hecho
echo en el servidor y restaurara tu mundo exactamente a como estaba
echo en tu archivo original de E: ("New World 8").
echo.
echo Cerrando servidores si estan abiertos...
taskkill /f /im java.exe 2>nul
timeout /t 2 /nobreak > nul

echo.
echo Restaurando archivos del mundo original...
if exist "server\world_backup_original" (
    rmdir /s /q "server\world" 2>nul
    xcopy /e /i /y "server\world_backup_original" "server\world" >nul
    echo.
    echo ==============================================================
    echo  LISTO! Tu mundo ha sido completamente restaurado como nuevo.
    echo  Ya puedes volver a iniciar el servidor con iniciar_todo.bat
    echo ==============================================================
) else (
    echo ERROR: No se encontro la carpeta server\world_backup_original.
)
echo.
pause
