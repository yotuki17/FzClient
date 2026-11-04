@echo off
chcp 65001 > nul
echo FzHUD をビルドします（初回は数分かかります）...
call gradlew.bat build
if errorlevel 1 (
  echo.
  echo ビルドに失敗しました。上のエラーをコピーしてClaudeに見せてください。
  pause
  exit /b 1
)
echo.
echo 完成: build\libs\fzhud-1.0.0.jar
explorer build\libs
pause
