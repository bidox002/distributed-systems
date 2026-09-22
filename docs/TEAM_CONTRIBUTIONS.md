# Remaining Team Contributions

The wireless network foundation is complete: `Peer`, `NodeDirectory`, `config/nodes.properties`, network-bound HTTP startup, and the health-check script are shared infrastructure. Do not replace those interfaces without team agreement.

## Member 1 and 2: Console interaction

- Add a non-blocking console loop to `Node`.
- Implement `chat <nodeId> <text>`: call `Clock.tick()`, create a message using the updated clock, and send it to the configured `Peer` with `NetworkClient`.
- Implement `score <player> <delta>` and `show` without adding REST endpoints outside the course brief.
- Capture cross-laptop chat evidence.

## Member 3 and 4: Token-ring correctness

- Add a token identifier and sequence number to the existing `/api/token` payload.
- Make duplicate token delivery idempotent so it cannot enter the critical section twice or produce a second token.
- Print token receipt, critical-section entry, score change, and successful transfer.
- Test concurrent score requests from at least three laptops and a failed successor node.

## Member 5 and 6: Bully election correctness

- When a higher node receives `ELECTION`, POST a separate `{ "type": "OK", "sender_id": ... }` payload to the original sender.
- Use an election timeout; only declare leadership if no higher node sends `OK`.
- Broadcast `COORDINATOR` to every reachable peer and test leader failure across the Wi-Fi network.

## Member 7 and 8: Automated testing and integration

- Add dependency-free Java tests for clock merge, message order, JSON validation, token handling, and election messages.
- Add five-laptop integration checks that use `config/nodes.properties`.
- Record T01-T10 outputs under `logs\` for the report.

## Member 9 and 10: Report and demonstration

- Create UML sequence diagrams for chat/clock merge, token-ring scoring, and Bully election.
- Maintain the contribution table, test evidence index, and five-laptop demonstration checklist.
- Rehearse Node 9 failure, Laptop E failure (Nodes 8 and 9), and quiet node recovery.
