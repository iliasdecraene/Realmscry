@echo off
rem Rebuilds Realmscry.jar from source. Requires a JDK on PATH (javac).
cd /d "%~dp0"
javac -cp "..\Sniffer\Tomato-v1.9.2.jar" -d out src\tracker\*.java
if errorlevel 1 (echo COMPILE FAILED & pause & exit /b 1)
copy /y "..\Sniffer\Tomato-v1.9.2.jar" Realmscry.jar >nul
copy /y web\index.html stage\web\index.html >nul
copy /y web\icon-16.png stage\web\icon-16.png >nul
copy /y web\icon-32.png stage\web\icon-32.png >nul
copy /y web\icon-64.png stage\web\icon-64.png >nul
"C:\Program Files\Java\jdk-25\bin\jar.exe" --update --file Realmscry.jar -m stage\manifest.mf -C out . -C stage trackerassets -C stage web
if errorlevel 1 (echo JAR UPDATE FAILED & pause & exit /b 1)
echo Built Realmscry.jar
pause
