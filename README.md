# bledob-ambilight-tv

Aplicativo para Android TV 14 que sincroniza fitas LED Bluetooth baratas (controladoras `BLEDOB`, `ELK-BLEDOM`, `MELK` e `LEDBLE`, que usam o app LotusLantern) com a imagem da TV em tempo real.

Comprei uma fita LED RGB dessas que usam o aplicativo LotusLantern e queria rodar um efeito parecido com o Ambilight direto na minha Smart TV Philips 4K com Android TV 14, sem precisar de placa de captura HDMI, Raspberry Pi ou câmera apontada para a tela. Como a própria TV tem Bluetooth Low Energy (BLE), este app roda um serviço em segundo plano (`MediaProjection` + GATT client), lê os quadros da tela em resolução reduzida (`64x36`) e manda a cor predominante das bordas direto para a fita.

## Como funciona

A captura de tela roda num `VirtualDisplay` de `64x36` pixels em `RGBA_8888`. Nessa resolução, varrer o buffer inteiro leva menos de `0.2 ms` na CPU da TV e não causa engasgos na reprodução de vídeos em 4K.

O cálculo de cor (`VividEdgeColorEngine`) descarta faixas pretas horizontais de filmes (letterbox), dá peso maior aos pixels próximos das bordas da imagem e aplica um ganho de saturação em HSV antes de suavizar a transição com média móvel exponencial (EMA). Isso evita que cenas com muitas cores misturadas deixem a fita sempre branca ou cinza.

Para o envio Bluetooth, o app conecta na característica `0000fff3-0000-1000-8000-00805f9b34fb` (serviço `0000fff0-0000-1000-8000-00805f9b34fb`) usando `WRITE_TYPE_NO_RESPONSE` e `CONNECTION_PRIORITY_HIGH`. Cada atualização de cor é um pacote de 9 bytes:

| Comando | Pacote Hex (`BLEDOB 0x07`) | Pacote Hex (`ELK-BLEDOM 0x04`) |
| :--- | :--- | :--- |
| Ligar (Power ON) | `7E 07 04 FF 00 01 02 01 EF` | `7E 04 04 F0 00 01 FF 00 EF` |
| Desligar (Power OFF) | `7E 07 04 00 00 00 02 01 EF` | `7E 04 04 00 00 00 FF 00 EF` |
| Cor RGB | `7E 07 05 03 [R] [G] [B] 10 EF` | `7E 04 05 03 [R] [G] [B] 00 EF` |
| Brilho (0 a 100%) | `7E 07 01 [00..64] FF FF FF 00 EF` | `7E 04 01 [00..64] FF FF FF 00 EF` |

Quando a TV entra em standby pelo controle remoto (`ACTION_SCREEN_OFF`), o serviço envia o pacote de desligamento para a fita não ficar acesa à noite e religa tudo quando a tela volta (`ACTION_SCREEN_ON`).

## Limitação de DRM (Netflix, Prime Video, Disney+)

No Android, aplicativos de streaming que usam Widevine L1 marcam a superfície de vídeo com `FLAG_SECURE`. Nesses aplicativos, a API `MediaProjection` recebe quadros pretos por bloqueio do próprio sistema operacional.

Já no YouTube, SmartTube, Stremio, Kodi, Plex, Jellyfin, canais de IPTV, Twitch e jogos, a captura de cores funciona normalmente. Quando o app detecta vários quadros pretos seguidos por causa de DRM, ele pode alternar automaticamente para o modo de áudio (`Visualizer` na sessão global da TV) ou manter uma luz âmbar de fundo até você voltar para um conteúdo sem DRM.

## Como instalar na Android TV

Você pode baixar o `.apk` na aba [Releases](../../releases) e instalar na TV (via ADB, LocalSend ou Send Files to TV).

Ao abrir o app na TV:
1. Clique em `Buscar Fita Automaticamente (Scan BLE)` para localizar qualquer fita `BLEDOB`, `BLEDOM`, `MELK` ou `LEDBLE` ligada por perto, ou digite o endereço MAC da sua fita (ex.: `BE:27:08:00:49:E2`) e clique em `Salvar MAC e Conectar`.
2. Use os botões `Vermelho`, `Verde` e `Azul` para conferir se as cores batem com a fita física. Se a sua fita tiver os fios invertidos, troque a opção `Ordem RGB` para `GRB` ou `BRG`.
3. Clique em `Iniciar Ambilight em Background` e depois em `Minimizar`.

### Tirando o pop-up de permissão e o ícone de gravação no Android TV 14

Por padrão, o Android 14 pede confirmação toda vez que um app inicia `MediaProjection` e mostra um ícone de transmissão no canto superior direito da TV. Se você tiver ADB por Wi-Fi ativado na TV, rode os comandos abaixo pelo PC (ou use o script `install_and_grant_tv.ps1`):

```powershell
adb connect IP_DA_SUA_TV:5555

# Permite iniciar o Ambilight sem a janela de confirmacao do Android
adb shell appops set com.lotus.ambilighttv PROJECT_MEDIA allow
adb shell appops set com.lotus.ambilighttv RECORD_AUDIO allow

# Evita que o Android feche o servico em segundo plano
adb shell cmd deviceidle whitelist +com.lotus.ambilighttv

# Oculta o icone de captura de tela no canto superior direito da Android TV 14
adb shell cmd device_config set_sync_disabled_for_tests persistent
adb shell cmd device_config put privacy media_projection_indicators_enabled false
```

No Windows, você também pode rodar direto:

```powershell
powershell -ExecutionPolicy Bypass -File .\install_and_grant_tv.ps1 -TvIpAddress "192.168.1.18"
```

## Compilando do código-fonte

Requer JDK 21 e Android SDK (API 35 instalada).

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

O APK compilado fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Licença

Distribuído sob a licença MIT. Veja o arquivo [LICENSE](LICENSE).
