# Servidor de Sinalizacao (Signaling Server)

Backend Node.js simples usado apenas para os dois celulares se "encontrarem"
e negociarem a conexao WebRTC. O video **nao passa por aqui** - ele viaja
direto entre os celulares.

## Rodar localmente

```bash
cd server
npm install
npm start
```

O servidor sobe em `ws://0.0.0.0:3000`. Para os celulares acessarem, use o
IP local do computador na rede Wi-Fi (ex: `ws://192.168.0.10:3000`).

Veja o `README.md` na raiz do projeto para o guia completo (backend + app
Android + como testar com dois celulares).
