# informações android - Compartilhamento de Tela em Tempo Real

App Android nativo (Kotlin) para compartilhar a tela de um celular com outro,
em tempo real, via WebRTC. Um celular e o **TRANSMISSOR** (compartilha a
tela), o outro e o **VISUALIZADOR** (assiste). Existe tambem uma
funcionalidade extra de **gravacao local** da tela no transmissor.

---

## Indice

1. [Visao geral da arquitetura](#1-visao-geral-da-arquitetura)
2. [Estrutura de pastas](#2-estrutura-de-pastas)
3. [Como rodar o backend](#3-como-rodar-o-backend)
4. [Como rodar o app no Android Studio](#4-como-rodar-o-app-no-android-studio)
5. [Como trocar o IP do servidor](#5-como-trocar-o-ip-do-servidor)
6. [Como testar com dois celulares no mesmo Wi-Fi](#6-como-testar-com-dois-celulares-no-mesmo-wi-fi)
7. [Como configurar STUN/TURN no futuro](#7-como-configurar-stunturn-no-futuro)
8. [Gravacao local de tela (feature extra)](#8-gravacao-local-de-tela-feature-extra)
9. [Privacidade e seguranca](#9-privacidade-e-seguranca)
10. [Fluxo do app explicado de forma simples](#10-fluxo-do-app-explicado-de-forma-simples)
11. [Solucao de problemas comuns](#11-solucao-de-problemas-comuns)

---

## 1. Visao geral da arquitetura

```
 ┌────────────────────┐        WebSocket (sinalizacao)        ┌────────────────────┐
 │   CELULAR           │ <-----------------------------------> │   CELULAR           │
 │   TRANSMISSOR        │            servidor Node.js           │   VISUALIZADOR       │
 │                       │            (server/server.js)         │                      │
 │  MediaProjection      │                                        │  SurfaceViewRenderer │
 │  -> WebRTC VideoTrack │ <========== video ao vivo (WebRTC) ==> │  <- VideoTrack       │
 └───────────────────────┘        conexao DIRETA (P2P)            └──────────────────────┘
```

Duas coisas viajam por caminhos DIFERENTES:

- **Sinalizacao** (quem esta online, ofertas/respostas WebRTC, ICE
  candidates): passa pelo **servidor Node.js**, via WebSocket. E so texto
  (JSON), bem leve.
- **Video** (a tela compartilhada em si): viaja **direto entre os dois
  celulares** (peer-to-peer), usando WebRTC. O servidor nunca ve o video.

Isso e o que faz o WebRTC ser eficiente para tempo real: depois que os dois
celulares se "apresentam" (via o servidor), o video nao depende mais do
servidor para fluir.

### Sala fixa

O app inteiro usa **uma unica sala fixa**: `sala-pedro-principal` (ver
`Constants.ROOM_ID`). Nao existe tela para criar ou digitar salas - isso e
proposital, para manter o app simples (um transmissor, um ou mais
visualizadores, sempre na mesma sala).

### Papel do aparelho (Transmissor ou Visualizador)

Na primeira vez que o app abre, ele pede um dos dois codigos fixos:

| Codigo       | Senha | Papel salvo   |
|--------------|-------|---------------|
| `Transmitir` | `123` | TRANSMISSOR   |
| `visualizar` | `123` | VISUALIZADOR  |

Esse papel e salvo localmente de forma **criptografada**
(`EncryptedSharedPreferences`, ver `data/LocalConfigManager.kt`) e lembrado
para sempre - o app nunca mais pede o codigo, a nao ser que o usuario clique
em "Redefinir configuração".

---

## 2. Estrutura de pastas

```
Aplicativo_K/
├── server/                          # Backend Node.js (sinalizacao WebSocket)
│   ├── package.json
│   ├── server.js                    # Servidor WebSocket + roteamento de mensagens
│   ├── rooms.js                     # Estado da sala fixa (em memoria)
│   └── README.md
│
└── app/src/main/java/com/pedro/screenshare/
    ├── data/
    │   ├── LocalConfigManager.kt     # Salva/le o papel do aparelho (criptografado)
    │   └── UserRole.kt               # Enum TRANSMITTER / VIEWER
    │
    ├── signaling/
    │   ├── SignalingClient.kt        # Cliente WebSocket (so rede, sem UI/WebRTC)
    │   ├── SignalingEvent.kt         # Nomes dos tipos de mensagem (protocolo)
    │   └── SignalingMessage.kt       # Estrutura da mensagem JSON
    │
    ├── webrtc/
    │   ├── PeerConnectionFactoryProvider.kt  # Inicializacao do WebRTC
    │   ├── ScreenShareManager.kt      # Tela -> VideoTrack (para transmitir)
    │   └── WebRtcClient.kt            # PeerConnections, offer/answer, ICE
    │
    ├── recording/
    │   └── ScreenRecordManager.kt     # Tela -> arquivo .mp4 local (feature extra)
    │
    ├── service/
    │   └── ScreenCaptureService.kt    # Foreground Service (mantem a captura viva)
    │
    ├── ui/
    │   ├── MainActivity.kt            # Decide para onde mandar o usuario
    │   ├── SetupActivity.kt           # Tela de primeira configuracao (codigo)
    │   ├── TransmitterActivity.kt     # Tela do transmissor
    │   └── ViewerActivity.kt          # Tela do visualizador
    │
    └── utils/
        ├── Constants.kt               # TODAS as constantes fixas do app
        └── PermissionUtils.kt         # Checagem de permissao de notificacao
```

Cada pasta tem uma responsabilidade e **nao se mistura** com as outras:
`signaling/` nao sabe nada de WebRTC, `webrtc/` nao sabe nada de WebSocket,
`service/` so cuida do ciclo de vida da captura, e as `ui/*Activity` sao as
unicas que "conversam" com todas as camadas (fazem o papel de coordenador).

---

## 3. Como rodar o backend

Requisito: [Node.js](https://nodejs.org) instalado (versao 18 ou mais recente).

```bash
cd server
npm install
npm start
```

Voce vera no terminal algo como:

```
Servidor de sinalizacao rodando na porta 3000
Sala fixa: sala-pedro-principal
Endereco WebSocket: ws://SEU_IP_LOCAL:3000
```

O servidor fica rodando enquanto o terminal estiver aberto. Para parar, use
`Ctrl+C`.

---

## 4. Como rodar o app no Android Studio

1. Abra o Android Studio -> **Open** -> selecione a pasta `Aplicativo_K`
   (a raiz do projeto, onde esta o `settings.gradle.kts`).
2. Aguarde o Gradle sincronizar (primeira vez pode demorar, ele baixa as
   dependencias, incluindo a biblioteca do WebRTC).
3. Configure `local.properties` com o endereco do servidor (veja a secao 5
   abaixo). Para producao fora da mesma rede Wi-Fi, veja tambem
   `PRODUCTION.md`.
4. Conecte um celular Android (modo desenvolvedor + depuracao USB ativados)
   ou use um emulador, e clique em **Run** (▶).
5. Instale o app em DOIS celulares (ou um celular + um emulador) para testar
   os dois papeis (transmissor e visualizador).

> O icone do app usado neste projeto e um icone padrao do proprio Android
> (placeholder), so para o projeto compilar sem precisar de arquivos de
> imagem. Troque quando quiser via **Image Asset Studio** do Android Studio
> (botao direito em `res` -> `New` -> `Image Asset`).

---

## 5. Como configurar o servidor do app

Os celulares precisam saber o endereco do servidor de sinalizacao. Esse valor
nao fica mais fixo no codigo: ele vem do `local.properties`, via Gradle.

Para teste local, use o IP do computador na mesma rede Wi-Fi:

```properties
signalingServerUrl=ws://192.168.0.10:3000
```

**Como descobrir o IP local do computador:**

- **Windows**: abra o Prompt de Comando e rode `ipconfig`. Procure por
  "Endereco IPv4" (algo como `192.168.0.10`).
- **Mac/Linux**: rode `ifconfig` ou `ip addr` no terminal.

Para producao, use uma URL publica com TLS:

```properties
signalingServerUrl=wss://signal.seu-dominio.com
signalingAuthToken=um-token-longo-e-secreto
```

Depois de editar, recompile o app (Run novamente) nos dois celulares.

---

## 6. Como testar com dois celulares no mesmo Wi-Fi

1. Rode o backend no computador (secao 3).
2. Garanta que **os dois celulares e o computador estao na MESMA rede
   Wi-Fi** (WebRTC/WebSocket local nao atravessa redes diferentes sem um
   servidor TURN - ver secao 7).
3. Instale o app nos dois celulares com o IP correto configurado (secao 5).
4. **No celular 1**: abra o app, digite `Transmitir`, senha `123` -> vira
   TRANSMISSOR.
5. **No celular 2**: abra o app, digite `visualizar`, senha `123` -> vira
   VISUALIZADOR.
6. **No transmissor**: clique em "Ficar online" (status muda para
   "Pronto").
7. **No visualizador**: clique em "Assistir tela" (entra na sala
   automaticamente).
8. **No transmissor**: clique em "Iniciar compartilhamento" e aceite o
   dialogo de permissao do Android.
9. **No visualizador**: o video da tela do transmissor deve aparecer
   automaticamente.
10. Para encerrar, clique em "Parar compartilhamento" no transmissor - o
    visualizador vera "Compartilhamento encerrado".

---

## 7. Como configurar STUN/TURN

O app ja le STUN/TURN do `local.properties`.

**STUN vs TURN, em uma frase:**
- STUN so ajuda os celulares a descobrirem seu proprio endereco publico.
- TURN retransmite o video quando uma conexao direta nao e possivel (ex:
  celulares em redes moveis/4G diferentes, atras de firewalls restritivos).

Para funcionar fora da mesma rede Wi-Fi/local (ex: um celular no 4G e outro
em outro Wi-Fi), configure um servidor TURN:

```properties
stunServerUrl=stun:stun.l.google.com:19302
turnServerUrl=turn:turn.seu-dominio.com:3478?transport=udp,turns:turn.seu-dominio.com:5349?transport=tcp
turnUsername=usuario-turn
turnPassword=senha-turn
```

Veja o guia completo em `PRODUCTION.md`.

---

## 8. Gravacao local de tela (feature extra)

Alem da transmissao ao vivo, o transmissor tem um botao **"Gravar tela"**
independente do compartilhamento:

- Pode gravar **sem** transmitir, transmitir **sem** gravar, ou os dois ao
  mesmo tempo.
- Grava **so vídeo** (sem audio), em um arquivo `.mp4`.
- O arquivo fica salvo em uma pasta **privada do proprio app**
  (`getExternalFilesDir()/Movies/gravacao_AAAAMMDD_HHmmss.mp4`), acessivel
  apenas por este app (ou manualmente via um gerenciador de arquivos,
  navegando ate `Android/data/com.pedro.screenshare/files/Movies`). Nao
  aparece na Galeria e nao e enviado a lugar nenhum - fica so no aparelho.
- Assim como o compartilhamento, exige que o usuario aceite o dialogo
  oficial do Android e mostra uma notificacao fixa enquanto grava.
- Implementada em `recording/ScreenRecordManager.kt`, controlada pelo mesmo
  `ScreenCaptureService`.

---

## 9. Privacidade e seguranca

- A tela **nunca** e capturada ou gravada sem o usuario clicar
  explicitamente no botao e aceitar o dialogo oficial do Android
  (`MediaProjectionManager`) - isso nao pode ser forcado pelo visualizador
  nem por mensagens de rede.
- Uma notificacao fixa e sempre exibida enquanto a tela esta sendo
  compartilhada e/ou gravada (exigencia do proprio Android e boa pratica de
  transparencia com o usuario).
- O compartilhamento pode ser parado a qualquer momento pelo transmissor.
- O video ao vivo **nunca** e salvo em arquivo - so a gravacao explicita
  (secao 8) gera um arquivo, e mesmo assim so localmente.
- O papel do aparelho (transmissor/visualizador) e salvo com
  `EncryptedSharedPreferences` (criptografado via Android Keystore).
- O servidor de sinalizacao nunca recebe nem armazena o video - so
  mensagens de texto (JSON) para negociacao WebRTC.

---

## 10. Fluxo do app explicado de forma simples

**Primeira vez que abre o app:**
> "Digite o codigo. Se for o codigo do transmissor, este celular vira o
> transmissor para sempre (ate redefinir). Se for o codigo do
> visualizador, vira visualizador para sempre."

**No transmissor, depois de configurado:**
> "Clique em Ficar online para avisar o servidor que voce existe. Clique em
> Iniciar compartilhamento quando quiser mostrar sua tela - o Android vai
> pedir sua confirmacao. Clique em Parar compartilhamento quando quiser
> parar. Se quiser, tambem pode Gravar a tela localmente, independente de
> estar transmitindo ou nao."

**No visualizador, depois de configurado:**
> "Clique em Assistir tela. O app entra sozinho na sala. Se o transmissor
> ja estiver compartilhando, o video aparece na hora. Se ele estiver online
> mas nao estiver compartilhando, voce ve uma mensagem avisando, e pode
> clicar em Solicitar compartilhamento para pedir (mas quem decide
> compartilhar e sempre o transmissor). Se o transmissor estiver offline,
> voce ve isso tambem."

**Se algo der errado:**
> "O status na tela sempre mostra o que esta acontecendo: Offline,
> Conectando, Pronto, Compartilhando, Erro de conexao, etc. Nunca fica
> tentando 'magicamente' sem avisar o usuario."

---

## 11. Solucao de problemas comuns

- **App nao conecta ("Erro de conexão")**: confira se o backend esta
  rodando, se `signalingServerUrl` em `local.properties` esta correto e se,
  em producao, o app usa `wss://` com dominio valido.
- **Video nao aparece mesmo com os dois "Prontos"**: confira se o
  transmissor realmente clicou em "Iniciar compartilhamento" e aceitou a
  permissao do Android.
- **Erro de build sobre a dependencia `io.getstream:stream-webrtc-android`**:
  essa biblioteca (fork do WebRTC mantido pela GetStream, ja que o artefato
  classico `org.webrtc:google-webrtc` nao tem mais um repositorio estavel)
  pode ter lancado uma versao mais nova. Veja a versao atual em
  https://github.com/GetStream/webrtc-android e atualize em
  `app/build.gradle.kts`.
- **"Já existe um transmissor ativo nesta sala"**: a sala fixa so permite
  UM transmissor por vez. Feche o app no outro celular que estava como
  transmissor, ou aguarde a conexao antiga cair.
