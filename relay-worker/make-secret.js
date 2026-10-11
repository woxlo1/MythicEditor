// Prints the secret for one friend's server:   node make-secret.js <RELAY_SECRET> <server-id>
// The friend puts it in config.yml (relay.secret) together with relay.server-id: <server-id>.
// The friend cannot work out your RELAY_SECRET (or anyone else's secret) from it.
const crypto = require("crypto");
const [master, id] = process.argv.slice(2);
if (!master || !id || !/^[a-z0-9][a-z0-9-]{1,30}$/.test(id) || id === "main") {
  console.error("usage: node make-secret.js <RELAY_SECRET> <server-id>");
  console.error("server-id: 2-31 characters, lower-case letters / digits / '-', not \"main\"");
  process.exit(1);
}
console.log(crypto.createHmac("sha256", master).update("relay:" + id).digest("hex"));
