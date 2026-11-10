import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { get } from 'node:http'
import { resolve } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'

const { chrome, profile, origin, path, cookie, gif } = JSON.parse(await Array.fromAsync(process.stdin)
  .then(chunks => Buffer.concat(chunks).toString()))
assert.match(origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/)
const browser = spawn(chrome, ['--headless', '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
  '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
  '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: 'ignore' })
const ended = new Promise(done => { browser.once('exit', done); browser.once('error', done) })
const waitFor = async (probe, label) => {
  for (let attempt = 0; attempt < 120; attempt++) {
    const value = await probe()
    if (value) return value
    await sleep(100)
  }
  throw Error('Timed out: ' + label)
}
let socket
const pending = new Map()
let next = 0
const cdp = (method, params = {}) => new Promise((done, fail) => {
  const id = ++next
  const timer = setTimeout(() => { pending.delete(id); fail(Error('Chrome command timed out: ' + method)) }, 4000)
  pending.set(id, { done, fail, timer })
  socket.send(JSON.stringify({ id, method, params }))
})
async function evaluate(expression) {
  const { result, exceptionDetails } = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (exceptionDetails) throw Error('Chrome evaluation failed')
  return result.value
}

try {
  const port = await waitFor(async () => {
    try { return Number((await readFile(resolve(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]) }
    catch { return 0 }
  }, 'DevTools port')
  const targets = await new Promise((done, fail) => {
    get(`http://127.0.0.1:${port}/json/list`, async response => {
      try { done(JSON.parse(Buffer.concat(await Array.fromAsync(response)).toString())) }
      catch (error) { fail(error) }
    }).on('error', fail)
  })
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl)
  await new Promise((done, fail) => { socket.onopen = done; socket.onerror = fail })
  socket.onmessage = event => {
    const message = JSON.parse(event.data), callback = pending.get(message.id)
    if (!callback) return
    pending.delete(message.id)
    clearTimeout(callback.timer)
    if (message.error) callback.fail(Error('Chrome command rejected'))
    else callback.done(message.result)
  }
  await cdp('Page.navigate', { url: origin + '/api/v1/health' })
  await waitFor(() => evaluate(`location.origin === ${JSON.stringify(origin)}`), 'same-origin page')
  const mediaStatus = () => evaluate(`fetch(${JSON.stringify(path)}, {cache: 'no-store'}).then(r => r.status)`)
  assert.equal(await mediaStatus(), 401)
  assert.ok((await cdp('Network.setCookie', { ...cookie, url: origin + '/', httpOnly: true, sameSite: 'Lax' })).success)
  assert.equal(await evaluate("fetch('/api/v1/auth/session').then(r => r.status)"), 200)
  const result = await evaluate(`(async () => {
    const r = await fetch(${JSON.stringify(path)})
    return [r.status, r.headers.get('content-type'), r.headers.get('cache-control'),
      btoa(String.fromCharCode(...new Uint8Array(await r.arrayBuffer())))]
  })()`)
  assert.deepEqual(result, [200, 'image/gif', 'no-store', gif])
} finally {
  if (socket?.readyState === WebSocket.OPEN) {
    try { await cdp('Browser.close') } catch { /* Chrome may close before replying */ }
    socket.close()
  }
  await Promise.race([ended, sleep(1500)])
  if (browser.exitCode === null && browser.signalCode === null) browser.kill()
  await ended
}
