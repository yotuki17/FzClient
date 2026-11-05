'use strict';
// The connection to FzClient in the game (mod: feature/LauncherLink.java). The launcher listens on 127.0.0.1 on a
// free port and passes -Dfzclient.link=<port>:<token> to the game; the game connects and says the token first.
// One JSON object per line both ways. Without the connection the overlay still records, it only can't show the
// game's numbers and switches.
const net = require('net');
const crypto = require('crypto');
const { EventEmitter } = require('events');

class Link extends EventEmitter {
  constructor(log) {
    super();
    this.log = log || (() => {});
    this.server = null;
    this.socket = null;
  }

  get connected() { return !!this.socket; }

  /** Starts listening; resolves to the system property for the game. */
  start() {
    this.token = crypto.randomBytes(16).toString('hex');
    return new Promise((resolve, reject) => {
      const server = net.createServer(socket => this.accept(socket));
      server.on('error', reject);
      server.listen(0, '127.0.0.1', () => {
        this.server = server;
        resolve('-Dfzclient.link=' + server.address().port + ':' + this.token);
      });
    });
  }

  accept(socket) {
    let buf = '', hello = false;
    socket.setEncoding('utf8');
    socket.setNoDelay(true);
    // a connection that does not say the token within 5 s is not the game
    const timer = setTimeout(() => { if (!hello) socket.destroy(); }, 5000);
    socket.on('data', chunk => {
      buf += chunk;
      if (buf.length > 1 << 20) { socket.destroy(); return; }
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (!hello) {
          if (msg.t !== 'hello' || msg.token !== this.token) { socket.destroy(); return; }
          hello = true;
          clearTimeout(timer);
          if (this.socket) this.socket.destroy();
          this.socket = socket;
          this.log('[link] game connected');
          this.emit('connected');
          continue;
        }
        if (this.socket === socket) this.emit('message', msg);
      }
    });
    const gone = () => {
      clearTimeout(timer);
      if (this.socket !== socket) return;
      this.socket = null;
      this.log('[link] game disconnected');
      this.emit('disconnected');
    };
    socket.on('close', gone);
    socket.on('error', gone);
  }

  send(msg) {
    if (this.socket) try { this.socket.write(JSON.stringify(msg) + '\n'); } catch { /* closing */ }
  }

  close() {
    if (this.socket) this.socket.destroy();
    this.socket = null;
    if (this.server) this.server.close();
    this.server = null;
  }
}

module.exports = { Link };
