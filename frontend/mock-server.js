// Simple mock server to emulate /api/keri/oobi for frontend testing
const express = require('express')
const app = express()
const port = process.env.PORT || 8080

app.get('/api/keri/oobi', (req, res) => {
  const session = req.header('X-Session-Id') || require('crypto').randomUUID()
  const oobi = `oobi://${session}/${require('crypto').randomUUID()}`
  res.json({ oobi })
})

app.get('/api/keri/status', (req, res) => {
  res.json({ status: 'ok', message: 'Mock backend', version: 'mock-1.0.0' })
})

app.listen(port, () => {
  console.log(`Mock backend listening on http://localhost:${port}`)
})

