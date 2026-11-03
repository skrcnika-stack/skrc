【測速提醒 APK 專案】
這個資料夾是「Android App 原始碼」，需要「打包」才會變成 APK。
最簡單的打包方式（免裝任何程式，約 5 分鐘）：
1. 到 github.com 免費註冊帳號，登入。
2. 右上角「+」→ New repository，名稱隨便取（例如 speedcam），按 Create repository。
3. 在新頁面點「uploading an existing file」，把這個 zip「解壓縮後的全部內容」拖進去（含 .github 資料夾），按 Commit changes。
   （若 .github 沒被拖進去：Add file → Create new file，檔名輸入 .github/workflows/build.yml，貼上本資料夾同名檔案內容。）
4. 點上方 Actions 分頁，等「打包 APK」出現綠色勾勾（約 3–6 分鐘）。
5. 點進那次執行，最下方 Artifacts 下載「測速提醒-APK」→ 解壓縮得到 app-debug.apk。
6. 傳到手機，點開安裝（要允許「安裝未知來源應用程式」）。
7. 第一次開啟選「允許定位（使用 App 時）」。
