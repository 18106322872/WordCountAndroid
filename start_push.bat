@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

REM 定位便携 Git（WorkBuddy 自带）或系统 Git
set "GITDIR=%USERPROFILE%\.workbuddy\vendor\PortableGit"
if not exist "%GITDIR%\usr\bin\bash.exe" (
  if exist "C:\Program Files\Git\bin\bash.exe" (
    set "GITDIR=C:\Program Files\Git"
  ) else if exist "C:\Program Files (x86)\Git\bin\bash.exe" (
    set "GITDIR=C:\Program Files (x86)\Git"
  )
)

if not exist "%GITDIR%\usr\bin\bash.exe" (
  echo ============================================================
  echo   找不到 Git（bash.exe）。
  echo   请安装 Git for Windows： https://git-scm.com/download/win
  echo   或者改用 GitHub Desktop（更简单，无需命令）。
  echo ============================================================
  pause
  exit /b 1
)

REM 把 git / bash 所在的目录加入 PATH，确保脚本内能调用 git
set "PATH=%GITDIR%\mingw64\bin;%GITDIR%\usr\bin;%PATH%"

echo ============================================================
echo   字数统计 App - 一键推送到 GitHub
echo   提示输入仓库地址时，粘贴：
echo   https://github.com/你的用户名/WordCountAndroid.git
echo ============================================================
echo.
"%GITDIR%\usr\bin\bash.exe" push.sh
echo.
echo （若有红字报错，把内容发给我；没问题就关掉窗口）
pause
