# GamesHub

Free HTML5 games marketplace. Every game runs in the browser - no installs, no accounts, no ads.

## How it works

```
GamesHub/
  index.html              <- Marketplace app (auto-discovers games)
  games-catalog.json      <- Auto-generated catalog
  games/
    snake/
      index.html          <- The game (HTML5 + CSS3 + JS)
      meta.json           <- Game metadata
    memory/
      index.html
      meta.json
    pong/
      index.html
      meta.json
```

1. Each game lives in its own folder under `games/`
2. Each folder has an `index.html` (the game) and a `meta.json` (metadata)
3. A GitHub Action scans all folders and generates `games-catalog.json`
4. The marketplace app reads the catalog and displays all games

## Adding a new game

1. Create a folder: `games/your-game-name/`
2. Add your HTML5 game as `index.html`
3. Add a `meta.json` with this schema:

```json
{
  "title": "Game Title",
  "description": "Short description of the game",
  "category": "Arcade",
  "tags": ["tag1", "tag2"],
  "author": "Your Name",
  "date": "2026-03-07",
  "version": "1.0.0",
  "controls": "Arrow keys to move",
  "difficulty": "Easy",
  "thumbnail": "",
  "color": "#22c55e"
}
```

4. Push to `main` - the catalog updates automatically

## Categories

Arcade, Puzzle, Action, Strategy, Racing, Sports, RPG, Casual

## Play Now

**Option 1 - GitHub Pages (recommended):**
Enable GitHub Pages in your repo settings (`Settings → Pages → Deploy from branch → main → / (root) → Save`). Your site will be live at `https://<your-username>.github.io/GamesHub/`

> **Note:** Opening `index.html` directly on GitHub.com will show the source code, not the rendered page. You must use GitHub Pages or a local server.

**Option 2 - Local server:**

```bash
npx serve .
```

Then open `http://localhost:3000` in your browser.

## License

MIT
