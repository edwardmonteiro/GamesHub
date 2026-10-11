# Ovelha de Giz — Android

A primeira fase completa é `index.html`. O app Android usa um WebView nativo com JavaScript e Canvas 2D, sem acesso à rede.

**Compilação oficial (Gradle + Android SDK):** `cd android && gradle :app:assembleDebug`.
Antes de compilar, copie `../index.html` para `android/app/src/main/assets/index.html`.

O workflow GitHub Actions compila, verifica a assinatura, publica o APK e disponibiliza o artefato da build.
A versão 1.0.2 substitui o empacotador binário experimental das versões 1.0.0/1.0.1.

**Assinatura:** build debug para testes de instalação. Versões posteriores exigirão uma chave de assinatura persistente para atualização sem desinstalação.
