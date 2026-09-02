# Realmscry party relay

A tiny Cloudflare Worker that lets party members share loot drops and boss
damage in real time. One Durable Object per party (addressed by the join
code), WebSocket push, last 100 events replayed to late joiners. No
accounts, no player data stored beyond the rolling event history.

## Deploy (once, via GitHub integration)

1. Cloudflare dashboard → **Workers & Pages → Create → Import a repository**.
2. Pick `iliasdecraene/Realmscry`.
3. Set **root directory** to `party-worker`, keep the suggested
   `npx wrangler deploy` deploy command.
4. Done — every push to `main` that touches this folder redeploys.

The worker then lives at `https://realmscry-party.<your-subdomain>.workers.dev`.
The tracker's party feature points at that URL (`-Dtracker.partyurl`
overrides it for testing).
