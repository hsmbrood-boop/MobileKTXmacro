@echo off
echo Gradle wrapper jar 복사 중...

set DEST=C:\MKTX\KtxMacro\gradle\wrapper\gradle-wrapper.jar

rem Android Studio 설치 경로에서 복사 시도
set SRC1=C:\Program Files\Android\Android Studio\plugins\android\lib\templates\gradle\wrapper\gradle-wrapper.jar
set SRC2=C:\Program Files\Android\Android Studio\plugins\android\resources\templates\gradle\wrapper\gradle-wrapper.jar

if exist "%SRC1%" (
    copy "%SRC1%" "%DEST%"
    echo 완료! gradle-wrapper.jar 복사됨
    goto done
)
if exist "%SRC2%" (
    copy "%SRC2%" "%DEST%"
    echo 완료! gradle-wrapper.jar 복사됨
    goto done
)

rem .gradle 캐시에서 찾기
for /r "%USERPROFILE%\.gradle\wrapper\dists" %%f in (gradle-wrapper.jar) do (
    copy "%%f" "%DEST%"
    echo 완료! %%f 복사됨
    goto done
)

echo 자동 복사 실패. 수동으로 진행 필요
:done
pause
