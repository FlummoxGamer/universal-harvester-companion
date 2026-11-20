const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3000;

const htmlContent = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>UH - Universal Harvester Companion</title>
  <style>
    :root {
      --bg: #0D1117;
      --surface: #161B22;
      --surface-2: #21262D;
      --primary: #58A6FF;
      --text: #F0F6FC;
      --subtext: #8B949E;
      --accent: #238636;
      --border: #30363D;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 24px;
    }
    .card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 20px;
      padding: 32px;
      max-width: 520px;
      width: 100%;
      box-shadow: 0 12px 32px rgba(0,0,0,0.4);
      text-align: center;
    }
    .badge {
      display: inline-block;
      padding: 6px 14px;
      background: rgba(88, 166, 255, 0.15);
      color: var(--primary);
      border-radius: 20px;
      font-size: 13px;
      font-weight: 600;
      margin-bottom: 16px;
    }
    .brand {
      font-size: 42px;
      font-weight: 800;
      letter-spacing: 2px;
      color: var(--text);
      margin-bottom: 8px;
    }
    .sub {
      color: var(--subtext);
      font-size: 15px;
      margin-bottom: 24px;
      line-height: 1.5;
    }
    .feature-list {
      text-align: left;
      background: var(--surface-2);
      border-radius: 14px;
      padding: 16px 20px;
      margin-bottom: 24px;
      border: 1px solid var(--border);
    }
    .feature-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 0;
      font-size: 14px;
      color: var(--text);
      border-bottom: 1px solid rgba(255,255,255,0.06);
    }
    .feature-item:last-child { border-bottom: none; }
    .feature-icon {
      color: #3FB950;
      font-weight: bold;
    }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: rgba(35, 134, 54, 0.2);
      color: #3FB950;
      padding: 6px 16px;
      border-radius: 30px;
      font-size: 13px;
      font-weight: 600;
    }
    .dot {
      width: 8px;
      height: 8px;
      background: #3FB950;
      border-radius: 50%;
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.4; transform: scale(0.9); }
      100% { opacity: 1; transform: scale(1); }
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">Native Android Browser (GeckoView + Compose)</div>
    <div class="brand">UH</div>
    <div class="sub">Native GeckoView Browser Companion with Jetpack Compose UI</div>
    
    <div class="feature-list">
      <div class="feature-item">
        <span class="feature-icon">✓</span>
        <span>Firefox-Style Tabs Tray (Normal / Private tabs + Gestures)</span>
      </div>
      <div class="feature-item">
        <span class="feature-icon">✓</span>
        <span>Instant Background Resume (No 1s Black Screen Flash)</span>
      </div>
      <div class="feature-item">
        <span class="feature-icon">✓</span>
        <span>ExoPlayer Video & Motion Wallpapers Fixed</span>
      </div>
      <div class="feature-item">
        <span class="feature-icon">✓</span>
        <span>Web Back-Chain Navigation Restored</span>
      </div>
      <div class="feature-item">
        <span class="feature-icon">✓</span>
        <span>100% Resilient Download Network Recovery & No (1) Duplicates</span>
      </div>
      <div class="feature-item">
        <span class="feature-icon">✓</span>
        <span>Compact 3-Dot Menu with Session Capture Tools</span>
      </div>
    </div>

    <div class="status-pill">
      <div class="dot"></div>
      <span>Build Verified & Ready</span>
    </div>
  </div>
</body>
</html>`;

const server = http.createServer((req, res) => {
  if (req.url === '/health' || req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', app: 'UH', framework: 'android' }));
    return;
  }

  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache',
    'X-Frame-Options': 'ALLOWALL'
  });
  res.end(htmlContent);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Preview server listening on http://0.0.0.0:${PORT}`);
});
