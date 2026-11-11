@echo off
rem The evia command for a plain EVIA Paradigma jar on Windows: evia-paradigma.jar next to this file, or %EVIA_JAR%.
setlocal
if "%EVIA_JAR%"=="" set "EVIA_JAR=%~dp0evia-paradigma.jar"
set "JAVA=java"
if not "%JAVA_HOME%"=="" set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" -Devia.cli=true -Djava.awt.headless=true -jar "%EVIA_JAR%" %*
