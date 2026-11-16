@echo off
where pythonw >nul 2>nul
if %errorlevel%==0 (
  start "" pythonw "%~dp0grill.py"
) else (
  start "" python "%~dp0grill.py"
)
exit
