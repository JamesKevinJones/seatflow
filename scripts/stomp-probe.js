// A STOMP-over-WebSocket subscriber with no dependencies.
//
// Node 22+ ships a global WebSocket, and STOMP frames are plain text ending in
// a NUL byte, so this needs no npm install - which matters because it runs in a
// throwaway container inside the compose network, addressing one backend
// replica directly rather than going through the load balancer.
//
//   node stomp-probe.js <ws-url> <destination> <seconds> <label>

const [, , url, destination, seconds, label] = process.argv;

const NUL = String.fromCharCode(0);
const frame = (command, headers, body = "") =>
  command + "\n" +
  Object.entries(headers).map(([k, v]) => k + ":" + v).join("\n") +
  "\n\n" + body + NUL;

const ws = new WebSocket(url);
const received = [];

ws.addEventListener("open", () => {
  ws.send(frame("CONNECT", { "accept-version": "1.2", host: "seatflow" }));
});

ws.addEventListener("message", (event) => {
  const text = typeof event.data === "string" ? event.data : "";
  const command = text.split("\n", 1)[0];

  if (command === "CONNECTED") {
    ws.send(frame("SUBSCRIBE", { id: "sub-0", destination }));
    console.log(label + " SUBSCRIBED " + destination);
    return;
  }

  if (command === "MESSAGE") {
    const body = text.slice(text.indexOf("\n\n") + 2).split(NUL)[0];
    received.push(body);
    console.log(label + " MESSAGE " + body);
  }
});

ws.addEventListener("error", (e) => console.log(label + " ERROR " + (e.message || e.type)));

setTimeout(() => {
  console.log(label + " TOTAL " + received.length);
  process.exit(0);
}, Number(seconds) * 1000);
