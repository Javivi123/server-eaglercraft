@echo off
cd /d "%~dp0"
start "1. Minecraft Paper 1.8.8 Server" cmd /k "cd /d ""%~dp0server"" && ""%~dp0jre\bin\java.exe"" -Xmx1024M -Xms512M -jar server.jar nogui"
ping 127.0.0.1 -n 6 >nul
start "2. BungeeCord Eaglercraft Proxy" cmd /k "cd /d ""%~dp0bungee"" && ""%~dp0jre\bin\java.exe"" -Deaglerxbungee.stfu=true -Xmx512M -Xms256M -jar bungee.jar"
