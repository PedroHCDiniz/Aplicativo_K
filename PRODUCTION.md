# Produção

Para funcionar com os celulares longe um do outro, o projeto precisa de duas partes publicas:

1. Servidor de sinalizacao WebSocket na internet.
2. Servidor TURN para garantir conexao WebRTC quando a conexao direta falhar.

## 1. Servidor de sinalizacao

Hospede a pasta `server/` em uma VPS ou plataforma Node.js. O comando de start ja esta pronto:

```bash
npm install
npm start
```

Configure estas variaveis no ambiente do servidor:

```bash
PORT=3000
PUBLIC_WS_URL=wss://signal.seu-dominio.com
SIGNALING_AUTH_TOKEN=um-token-longo-e-secreto
```

Em producao, use dominio com TLS para o app acessar via `wss://`.

A rota abaixo pode ser usada como health check da hospedagem:

```text
https://signal.seu-dominio.com/healthz
```

## 2. Servidor TURN

Para caso real, use um TURN proprio ou contratado. Com `coturn`, voce normalmente tera URLs parecidas com:

```text
turn:turn.seu-dominio.com:3478?transport=udp
turns:turn.seu-dominio.com:5349?transport=tcp
```

O TURN precisa de usuario e senha. Guarde esses valores para preencher o app.

## 3. Configurar o app Android

Copie `local.properties.example` para `local.properties` e preencha:

```properties
signalingServerUrl=wss://signal.seu-dominio.com
signalingAuthToken=o-mesmo-token-do-servidor
stunServerUrl=stun:stun.l.google.com:19302
turnServerUrl=turn:turn.seu-dominio.com:3478?transport=udp,turns:turn.seu-dominio.com:5349?transport=tcp
turnUsername=usuario-turn
turnPassword=senha-turn
```

Depois recompile e reinstale o app nos celulares.

## Observacao importante

O token atual protege o WebSocket contra conexoes casuais, mas nao substitui login real. Para produto comercial, o proximo passo e autenticar usuarios/aparelhos em um backend com contas, sessoes e autorizacao por sala.
