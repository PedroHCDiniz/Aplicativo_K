/**
 * server.js
 * ---------------------------------------------------------------------------
 * Servidor de SINALIZACAO (signaling) para o app de compartilhamento de tela.
 *
 * O QUE ESTE SERVIDOR FAZ:
 *   - Mantem uma unica sala fixa: "sala-pedro-principal".
 *   - Sabe quem e o transmissor (so pode existir 1 por vez) e quem sao os
 *     visualizadores conectados.
 *   - Repassa (relay) mensagens de WebRTC (offer/answer/ice-candidate) entre
 *     o transmissor e cada visualizador, para que os dois celulares consigam
 *     montar uma conexao DIRETA de video (peer-to-peer) entre eles.
 *
 * O QUE ESTE SERVIDOR *NAO* FAZ:
 *   - NAO transmite video. O video (tela compartilhada) viaja direto entre os
 *     dois celulares via WebRTC, sem passar pelo servidor. O servidor so troca
 *     "cartas" de apresentacao (SDP) e "enderecos de rede" (ICE candidates)
 *     necessarios para os celulares se encontrarem.
 *   - NAO grava nada, NAO guarda video, NAO tem banco de dados.
 *
 * CONCEITO IMPORTANTE - WebSocket:
 *   WebSocket e uma conexao permanente (fica aberta) entre o celular e o
 *   servidor, diferente de uma requisicao HTTP normal (que abre e fecha na
 *   hora). Isso permite o servidor "empurrar" mensagens para o celular a
 *   qualquer momento (por exemplo, avisar o visualizador que o transmissor
 *   ficou offline), sem o celular precisar ficar perguntando toda hora.
 *
 * CONCEITO IMPORTANTE - Offer/Answer/ICE (WebRTC):
 *   Para dois dispositivos conseguirem trocar video diretamente, eles
 *   precisam primeiro negociar (via este servidor) como vao se conectar:
 *     1. O transmissor cria uma "OFFER" (oferta) descrevendo o video que ele
 *        quer enviar (codec, resolucao, etc) e manda pelo servidor.
 *     2. O visualizador recebe a oferta, cria uma "ANSWER" (resposta) e manda
 *        de volta pelo servidor.
 *     3. Os dois lados trocam "ICE CANDIDATES", que sao possiveis caminhos de
 *        rede (IP/porta) para tentar a conexao direta (ex: mesma rede Wi-Fi,
 *        ou via internet usando um servidor STUN/TURN).
 *   Depois que essa negociacao termina, o video passa a fluir DIRETO entre os
 *   celulares - o servidor so serviu de "cartorio" para apresentar os dois.
 */

const http = require('http');
const WebSocket = require('ws');
const crypto = require('crypto');
const { getOrCreateRoom } = require('./rooms');

// Porta do servidor. Pode ser sobrescrita pela variavel de ambiente PORT.
const PORT = process.env.PORT || 3000;

// URL publica apenas para log/diagnostico em producao. Ex:
// PUBLIC_WS_URL=wss://seu-dominio.com
const PUBLIC_WS_URL = process.env.PUBLIC_WS_URL || `ws://SEU_IP_LOCAL:${PORT}`;

// Token opcional para nao deixar o WebSocket publico totalmente aberto.
// Se SIGNALING_AUTH_TOKEN estiver vazio, o servidor aceita conexoes sem token
// (bom para teste local). Em producao, configure um valor forte.
const SIGNALING_AUTH_TOKEN = process.env.SIGNALING_AUTH_TOKEN || '';

// A sala fixa usada pelo app inteiro (ver Constants.kt no app Android).
// Isso repete o texto exato usado no app - se mudar aqui, tem que mudar la.
const FIXED_ROOM_ID = 'sala-pedro-principal';

// Servidor HTTP "puro" que so existe para o WebSocket se conectar em cima
// dele. A rota /healthz ajuda plataformas de hospedagem a verificar se o
// processo esta vivo.
const httpServer = http.createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, roomId: FIXED_ROOM_ID }));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

// Servidor WebSocket, "grudado" no servidor HTTP acima.
const wss = new WebSocket.Server({ server: httpServer });

/**
 * Envia um objeto JSON para um socket, se ele ainda estiver aberto.
 * Centralizamos isso aqui para nao repetir a checagem "socket esta aberto?"
 * em cada lugar do codigo.
 */
function sendJson(socket, data) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(data));
  }
}

/**
 * Envia uma mensagem de erro simples para o socket e (opcionalmente) fecha.
 */
function sendError(socket, message) {
  sendJson(socket, { type: 'error', message });
}

/**
 * Avisa TODOS os visualizadores de uma sala sobre alguma coisa
 * (ex: "transmitter-online", "sharing-started", etc).
 */
function broadcastToViewers(room, data) {
  for (const viewerSocket of room.viewers.values()) {
    sendJson(viewerSocket, data);
  }
}

// Quando um novo celular (cliente) conecta no WebSocket...
wss.on('connection', (socket, request) => {
  if (!isAuthorized(request)) {
    socket.close(1008, 'Nao autorizado');
    return;
  }

  // Cada conexao guarda seu proprio "estado" diretamente no objeto socket,
  // para sabermos depois (ex: quando desconectar) quem ele era.
  socket.role = null; // 'transmitter' | 'viewer'
  socket.roomId = null;
  socket.clientId = null; // usado so para visualizadores (id unico por conexao)

  socket.on('message', (rawMessage) => {
    let message;
    try {
      message = JSON.parse(rawMessage.toString());
    } catch (err) {
      sendError(socket, 'Mensagem invalida (JSON malformado).');
      return;
    }
    handleMessage(socket, message);
  });

  socket.on('close', () => {
    handleDisconnect(socket);
  });
});

function isAuthorized(request) {
  if (!SIGNALING_AUTH_TOKEN) return true;

  const authorization = request.headers.authorization || '';
  return authorization === `Bearer ${SIGNALING_AUTH_TOKEN}`;
}

/**
 * Roteador principal: olha o campo "type" da mensagem recebida e decide o
 * que fazer. Cada "case" corresponde a um dos eventos WebSocket do app.
 */
function handleMessage(socket, message) {
  const { type } = message;

  // Por enquanto o app so usa a sala fixa, mas sempre lemos o roomId
  // enviado pelo cliente (com fallback pra sala fixa) para o codigo do
  // servidor nao depender de uma variavel global escondida.
  const roomId = message.roomId || FIXED_ROOM_ID;
  const room = getOrCreateRoom(roomId);

  switch (type) {
    case 'register-transmitter':
      handleRegisterTransmitter(socket, room);
      break;

    case 'register-viewer':
      handleRegisterViewer(socket, room);
      break;

    case 'start-share-request':
      // O visualizador esta pedindo para o transmissor iniciar. O servidor
      // so repassa o pedido - quem decide se compartilha ou nao e sempre o
      // usuario do celular transmissor (ele precisa clicar no botao dele).
      if (room.transmitter) {
        sendJson(room.transmitter.socket, {
          type: 'start-share-request',
          viewerId: socket.clientId,
        });
      }
      break;

    case 'sharing-started':
      handleSharingStarted(socket, room);
      break;

    case 'sharing-stopped':
      room.isSharing = false;
      broadcastToViewers(room, { type: 'sharing-stopped' });
      break;

    // As tres mensagens abaixo (offer/answer/ice-candidate) sao o "coracao"
    // da negociacao WebRTC. O servidor NAO entende o conteudo delas - ele
    // so olha "de quem veio" e "pra quem vai" e repassa (relay) para frente.
    case 'offer':
      relayOfferOrCandidateFromTransmitter(socket, room, message, 'offer');
      break;

    case 'answer':
      relayFromViewerToTransmitter(socket, room, message, 'answer');
      break;

    case 'ice-candidate':
      // O ICE candidate pode vir tanto do transmissor quanto do visualizador,
      // entao checamos o "role" de quem enviou para saber pra onde mandar.
      if (socket.role === 'transmitter') {
        relayOfferOrCandidateFromTransmitter(socket, room, message, 'ice-candidate');
      } else if (socket.role === 'viewer') {
        relayFromViewerToTransmitter(socket, room, message, 'ice-candidate');
      }
      break;

    default:
      sendError(socket, `Tipo de mensagem desconhecido: ${type}`);
  }
}

/**
 * Registra este socket como O transmissor da sala.
 * Regra importante: so pode existir 1 transmissor ativo por sala.
 */
function handleRegisterTransmitter(socket, room) {
  if (room.transmitter && room.transmitter.socket.readyState === WebSocket.OPEN) {
    sendJson(room.transmitter.socket, {
      type: 'error',
      message: 'Outro transmissor assumiu esta sala.',
    });
    room.transmitter.socket.close(1000, 'Substituido por novo transmissor');
  }

  socket.role = 'transmitter';
  socket.roomId = room.roomId;
  room.transmitter = { id: 'transmitter', socket };
  room.isSharing = false; // um transmissor que acabou de entrar nunca esta compartilhando ainda

  // Avisa todos os visualizadores ja conectados que o transmissor chegou.
  broadcastToViewers(room, { type: 'transmitter-online' });
}

/**
 * Registra este socket como um visualizador da sala.
 * Cada visualizador ganha um "clientId" unico, usado para o servidor saber
 * para qual visualizador especifico repassar cada offer/ice-candidate
 * (o transmissor pode ter varios visualizadores assistindo ao mesmo tempo).
 */
function handleRegisterViewer(socket, room) {
  const viewerId = crypto.randomUUID();
  socket.role = 'viewer';
  socket.roomId = room.roomId;
  socket.clientId = viewerId;
  room.viewers.set(viewerId, socket);

  // Informa o visualizador sobre o estado atual do transmissor.
  if (room.transmitter) {
    sendJson(socket, { type: 'transmitter-online' });
    if (room.isSharing) {
      // Ja esta compartilhando: avisa o visualizador E avisa o transmissor
      // que existe um novo interessado (o transmissor vai criar uma OFFER
      // especifica para este viewerId).
      sendJson(socket, { type: 'sharing-started' });
      sendJson(room.transmitter.socket, { type: 'viewer-online', viewerId });
    } else {
      // Transmissor online mas parado: so avisa que o viewer existe, sem
      // pedir oferta (o video so comeca quando o transmissor clicar em
      // "Iniciar compartilhamento").
      sendJson(room.transmitter.socket, { type: 'viewer-online', viewerId });
    }
  } else {
    sendJson(socket, { type: 'transmitter-offline' });
  }
}

/**
 * Quando o transmissor avisa que comecou a compartilhar, marcamos a sala
 * como "isSharing = true" e devolvemos pro proprio transmissor a lista de
 * visualizadores ja conectados (para ele criar uma oferta/PeerConnection
 * para cada um). Tambem avisamos cada visualizador que o compartilhamento
 * comecou.
 */
function handleSharingStarted(socket, room) {
  if (socket.role !== 'transmitter') return;

  room.isSharing = true;
  const viewerIds = Array.from(room.viewers.keys());

  sendJson(socket, { type: 'sharing-started', viewerIds });
  broadcastToViewers(room, { type: 'sharing-started' });
}

/**
 * Repassa uma mensagem que veio DO TRANSMISSOR para UM visualizador
 * especifico (usado para offer e ice-candidate). A mensagem precisa trazer
 * o campo "viewerId" dizendo para qual visualizador ela e destinada, porque
 * pode haver mais de um visualizador conectado ao mesmo tempo.
 */
function relayOfferOrCandidateFromTransmitter(socket, room, message, type) {
  if (socket.role !== 'transmitter') return;

  const targetViewerSocket = room.viewers.get(message.viewerId);
  if (!targetViewerSocket) return;

  sendJson(targetViewerSocket, {
    type,
    sdp: message.sdp,
    candidate: message.candidate,
  });
}

/**
 * Repassa uma mensagem que veio DE UM visualizador para o transmissor
 * (usado para answer e ice-candidate). Aqui o servidor "carimba" a mensagem
 * com o viewerId de quem enviou (o proprio id que o servidor gerou ao
 * registrar o visualizador), para o transmissor saber a qual PeerConnection
 * (de qual viewer) essa resposta pertence.
 */
function relayFromViewerToTransmitter(socket, room, message, type) {
  if (socket.role !== 'viewer' || !room.transmitter) return;

  sendJson(room.transmitter.socket, {
    type,
    sdp: message.sdp,
    candidate: message.candidate,
    viewerId: socket.clientId,
  });
}

/**
 * Limpa o estado da sala quando um socket desconecta (fecha o app, perde
 * internet, etc). Isso evita "fantasmas": um transmissor caido que ainda
 * aparece como online, ou um visualizador caido ocupando uma PeerConnection
 * no transmissor para sempre.
 */
function handleDisconnect(socket) {
  if (!socket.roomId) return; // nunca chegou a se registrar em uma sala

  const room = getOrCreateRoom(socket.roomId);

  if (socket.role === 'transmitter') {
    if (!room.transmitter || room.transmitter.socket !== socket) {
      return;
    }

    room.transmitter = null;
    room.isSharing = false;
    broadcastToViewers(room, { type: 'transmitter-offline' });
    broadcastToViewers(room, { type: 'sharing-stopped' });
  } else if (socket.role === 'viewer') {
    room.viewers.delete(socket.clientId);
    // Evento interno (nao esta na lista original pedida, mas e necessario
    // para o transmissor liberar a PeerConnection e nao vazar memoria):
    // avisamos o transmissor que este viewer especifico saiu.
    if (room.transmitter) {
      sendJson(room.transmitter.socket, { type: 'viewer-offline', viewerId: socket.clientId });
    }
  }
}

httpServer.listen(PORT, () => {
  console.log(`Servidor de sinalizacao rodando na porta ${PORT}`);
  console.log(`Sala fixa: ${FIXED_ROOM_ID}`);
  console.log(`Endereco WebSocket: ${PUBLIC_WS_URL}`);
  console.log(`Auth token: ${SIGNALING_AUTH_TOKEN ? 'ativado' : 'desativado'}`);
});
