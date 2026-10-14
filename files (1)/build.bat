@echo off
chcp 65001 >nul
echo [NationWar] JDK 25 + Maven 이 설치되어 있어야 합니다.
call mvn -B clean package
if errorlevel 1 (
  echo 빌드 실패. 위 오류 로그를 확인하세요.
  pause
  exit /b 1
)
echo.
echo 완료! target 폴더의 NationWar-3.0.0.jar 를 서버 plugins 폴더에 넣으세요.
pause
