/**
 * rooms.js
 * ---------------------------------------------------------------------------
 * Este arquivo controla o ESTADO das salas em memoria (RAM).
 *
 * IMPORTANTE (sala fixa):
 * O app so usa UMA sala fixa: "sala-pedro-principal". Este arquivo foi escrito
 * de forma generica (um Map de salas) apenas para deixar o codigo organizado,
 * mas na pratica o server.js so vai criar/usar essa unica sala. Nao existe
 * nenhuma tela ou rota para o usuario criar salas novas.
 *
 * Cada sala guarda:
 *   - transmitter: { id, socket } do celular transmissor conectado (ou null)
 *   - isSharing: se o transmissor esta compartilhando a tela agora
 *   - viewers: Map<viewerId, socket> com todos os visualizadores conectados
 *
 * Por que guardar isso em memoria (RAM) e nao em banco de dados?
 * Porque e um MVP simples: se o servidor reiniciar, todo mundo precisa
 * reconectar (o app faz isso sozinho quando o usuario clica em "Ficar online"
 * ou "Assistir tela" de novo). Não precisamos persistir nada em disco.
 */

// Guarda todas as salas: chave = roomId, valor = objeto da sala.
const rooms = new Map();

/**
 * Retorna a sala pelo id. Se ela ainda nao existir, cria uma sala vazia.
 * Isso garante que a "sala-pedro-principal" sempre exista assim que alguem
 * tentar entrar nela, sem precisar de um passo de "criar sala" separado.
 */
function getOrCreateRoom(roomId) {
  if (!rooms.has(roomId)) {
    rooms.set(roomId, {
      roomId,
      transmitter: null, // { id, socket } | null
      isSharing: false,
      viewers: new Map(), // viewerId -> socket
    });
  }
  return rooms.get(roomId);
}

module.exports = {
  rooms,
  getOrCreateRoom,
};
